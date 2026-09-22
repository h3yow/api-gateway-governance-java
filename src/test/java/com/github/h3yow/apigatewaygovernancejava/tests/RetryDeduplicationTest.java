package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import com.github.h3yow.apigatewaygovernancejava.governance.downstream.DownstreamRequest;
import com.github.h3yow.apigatewaygovernancejava.governance.downstream.DownstreamResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.downstream.SimulatedDownstreamService;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.IdempotencyStore;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.InMemoryIdempotencyStore;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.RetryExecutor;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试去重测试：
 * 超时/连接失败可重试、业务拒绝不重试、写操作重试不重复生效、
 * 并发同幂等键只生效一次、错误类型可区分。
 */
class RetryDeduplicationTest {

    private SimulatedDownstreamService downstream;
    private RetryExecutor executor;
    private IdempotencyStore store;

    @BeforeEach
    void setUp() {
        downstream = new SimulatedDownstreamService();
        executor = new RetryExecutor();
        store = new InMemoryIdempotencyStore();
    }

    private DownstreamRequest writeRequest(String key, String scenario) {
        return new DownstreamRequest("orders", key, Map.of("itemId", "sku-1"), true, scenario);
    }

    @Test
    void timeoutIsRetriedAndWriteEffectHappensOnce() {
        // 首次：下游已生效但响应超时；重试：同幂等键重放成功。
        DownstreamResponse response = executor.execute(
                RetryPolicy.of(3, 1L), "key-timeout",
                () -> downstream.invoke(writeRequest("key-timeout", "timeout")));

        assertTrue(response.success());
        assertTrue(response.deduplicated(), "重试结果必须标记为幂等重放");
        assertEquals(1L, downstream.writeEffectCount(),
                "超时重试绝不允许写操作重复生效");
        assertEquals(2L, downstream.invocationCount(),
                "首次超时 + 一次重试 = 2 次实际下游调用");
        TestLogger.log("超时重试去重",
                "write key=key-timeout scenario=timeout, maxAttempts=3",
                "TIMEOUT 可重试；下游按幂等键只生效一次，重试命中台账",
                "成功 ref=" + response.reference() + " effectCount=1 invocationCount=2");
    }

    @Test
    void connectionFailureIsRetriedThenSucceeds() {
        // connection 场景每次都失败 -> 重试耗尽，错误类型确定为 CONNECTION_FAILED。
        GovernanceException ex = assertThrows(GovernanceException.class, () -> executor.execute(
                RetryPolicy.of(3, 1L), "key-conn",
                () -> downstream.invoke(writeRequest("key-conn", "connection"))));
        assertEquals(ErrorCode.DOWNSTREAM_CONNECTION_FAILED, ex.errorCode());
        assertEquals(502, ex.errorCode().httpStatus());
        assertEquals(3L, downstream.invocationCount(), "连接失败应重试满 3 次");
        assertEquals(0L, downstream.writeEffectCount(), "连接失败阶段写操作未生效");
        TestLogger.log("连接失败重试耗尽",
                "write key=key-conn scenario=connection, maxAttempts=3",
                "CONNECTION_FAILED 可重试；连接未达下游故无生效计数",
                "归一化 code=" + ex.errorCode().reasonCode() + " http=502 invocations=3");
    }

    @Test
    void businessRejectionIsNotRetried() {
        GovernanceException ex = assertThrows(GovernanceException.class, () -> executor.execute(
                RetryPolicy.of(5, 1L), "key-biz",
                () -> downstream.invoke(writeRequest("key-biz", "business"))));
        assertEquals(ErrorCode.DOWNSTREAM_BUSINESS_REJECTED, ex.errorCode());
        assertEquals(422, ex.errorCode().httpStatus());
        assertEquals(1L, downstream.invocationCount(),
                "业务拒绝结果确定，必须立即返回不重试");
        TestLogger.log("业务拒绝不重试",
                "write key=key-biz scenario=business, maxAttempts=5",
                "BUSINESS_REJECTED.retryable=false，避免对确定结果重复施压",
                "归一化 code=" + ex.errorCode().reasonCode() + " http=422 invocations=1");
    }

    @Test
    void writeWithoutIdempotencyKeyIsRejected() {
        GovernanceException ex = assertThrows(GovernanceException.class, () -> {
            executor.requireIdempotencyKeyForWrite(true, "  ");
            executor.execute(RetryPolicy.of(3, 1L), null,
                    () -> downstream.invoke(new DownstreamRequest("orders", null, Map.of(), true, null)));
        });
        assertEquals(ErrorCode.MISSING_IDEMPOTENCY_KEY, ex.errorCode());
        TestLogger.log("写操作缺幂等键",
                "write key=blank",
                "无幂等键则无法保证重试安全，入口前置拒绝",
                "拒绝 code=" + ex.errorCode().reasonCode() + " http=400");
    }

    @Test
    void concurrentSameIdempotencyKeyEffectsOnce() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IdempotencyStore.IdempotentOutcome<DownstreamResponse> outcome =
                            store.execute("hot-key", () -> {
                                executed.incrementAndGet();
                                return downstream.invoke(writeRequest("hot-key", null));
                            });
                    switch (outcome) {
                        case IdempotencyStore.IdempotentOutcome.Executed<DownstreamResponse> e -> { }
                        case IdempotencyStore.IdempotentOutcome.Replayed<DownstreamResponse> r -> replayed.incrementAndGet();
                        case IdempotencyStore.IdempotentOutcome.InFlight<DownstreamResponse> inf -> inFlight.incrementAndGet();
                    }
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        TestLogger.log("并发同键去重",
                threads + " 个线程同一幂等键 hot-key",
                "putIfAbsent 抢执行权 + CountDownLatch 等待首结果重放",
                "executed=" + executed.get() + " replayed=" + replayed.get()
                        + " inFlight=" + inFlight.get() + " failures=" + failures.get()
                        + " | downstreamEffect=" + downstream.writeEffectCount());

        assertEquals(1, executed.get(), "业务动作只能被执行一次");
        assertEquals(1L, downstream.writeEffectCount(), "下游生效次数必须为 1");
        assertEquals(0, failures.get(), "等待/重放路径不应抛出");
        assertEquals(threads - 1, replayed.get() + inFlight.get(),
                "其余线程必须为重放或在途提示，不能二次执行");
    }

    @Test
    void repeatedRequestsReplaySameReference() {
        DownstreamResponse first = store.execute("dup-key",
                () -> downstream.invoke(writeRequest("dup-key", null))) instanceof
                IdempotencyStore.IdempotentOutcome.Executed<DownstreamResponse> e ? e.value() : null;
        var second = store.execute("dup-key",
                () -> downstream.invoke(writeRequest("dup-key", null)));
        assertTrue(second instanceof IdempotencyStore.IdempotentOutcome.Replayed<DownstreamResponse>);
        @SuppressWarnings("unchecked")
        String secondRef = ((IdempotencyStore.IdempotentOutcome.Replayed<DownstreamResponse>) second)
                .value().reference();
        assertEquals(first.reference(), secondRef, "重复请求必须返回同一业务引用");
        assertEquals(1L, downstream.writeEffectCount());
        TestLogger.log("重复请求重放",
                "同一幂等键连续两次完整请求",
                "第二次命中 COMPLETED 台账，直接返回缓存结果",
                "ref1=" + first.reference() + " ref2=" + secondRef + " effectCount=1");
    }

    @Test
    void errorTypesMapToDistinguishableCodes() {
        // 未知内部错误不重试且对外脱敏为 500。
        GovernanceException unknown = assertThrows(GovernanceException.class, () -> executor.execute(
                RetryPolicy.of(3, 1L), null,
                () -> downstream.invoke(
                        new DownstreamRequest("orders", null, Map.of(), false, "unknown"))));
        assertEquals(ErrorCode.DOWNSTREAM_UNKNOWN_ERROR, unknown.errorCode());
        assertEquals(500, unknown.errorCode().httpStatus());
        assertEquals(1L, downstream.invocationCount(), "未知错误不得重试");
        // 内部细节（internal-detail-should-not-leak）不得出现在对外文案中。
        assertFalse(unknown.getMessage().contains("internal-detail"),
                "内部异常细节不得透出给调用方");
        TestLogger.log("错误类型归一化",
                "scenario=unknown（内部 IllegalStateException 细节）",
                "未归类异常不重试 + 响应脱敏，细节仅入日志",
                "对外 code=" + unknown.errorCode().reasonCode()
                        + " message=\"" + unknown.getMessage() + "\"（无内部细节）");
    }
}
