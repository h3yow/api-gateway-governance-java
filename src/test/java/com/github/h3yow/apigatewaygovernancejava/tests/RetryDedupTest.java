package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.resilience.BusinessRejectionException;
import com.github.h3yow.apigatewaygovernancejava.resilience.DownstreamCall;
import com.github.h3yow.apigatewaygovernancejava.resilience.ErrorType;
import com.github.h3yow.apigatewaygovernancejava.resilience.IdempotencyStore;
import com.github.h3yow.apigatewaygovernancejava.resilience.RetryOutcome;
import com.github.h3yow.apigatewaygovernancejava.resilience.RetryPolicy;
import com.github.h3yow.apigatewaygovernancejava.resilience.SafeRetryExecutor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 失败归一化、安全重试与写操作幂等去重测试。
 * 每个用例打印请求输入、判定依据与最终结果。
 */
class RetryDedupTest {

    private static final Logger log = LoggerFactory.getLogger(RetryDedupTest.class);

    private SafeRetryExecutor executor(int readAttempts, int writeAttempts) {
        IdempotencyStore store = new IdempotencyStore();
        return new SafeRetryExecutor(
                new RetryPolicy(readAttempts, 5,
                        java.util.Set.of(ErrorType.TIMEOUT, ErrorType.CONNECTION_FAILURE)),
                new RetryPolicy(writeAttempts, 5,
                        java.util.Set.of(ErrorType.CONNECTION_FAILURE)),
                store);
    }

    @Test
    void readTimeoutIsClassifiedAndRetriedThenFails() {
        SafeRetryExecutor executor = executor(3, 2);
        AtomicInteger calls = new AtomicInteger();
        DownstreamCall<String> call = () -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException("internal socket detail");
        };
        RetryOutcome<String> out = executor.executeRead(call);
        log.info("请求输入=读操作持续超时；判定依据：TIMEOUT 可重试，最多3次；结果 success={} errorType={} attempts={} message={}",
                out.success(), out.errorType(), out.attempts(), out.safeMessage());
        assertFalse(out.success());
        assertEquals(ErrorType.TIMEOUT, out.errorType());
        assertEquals(3, out.attempts());
        assertEquals(3, calls.get());
        assertFalse(out.safeMessage().contains("internal socket detail"), "内部细节不得透出");
    }

    @Test
    void readRecoversAfterConnectionFailure() {
        SafeRetryExecutor executor = executor(3, 2);
        AtomicInteger calls = new AtomicInteger();
        DownstreamCall<String> call = () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ConnectException("connection refused detail");
            }
            return "ok";
        };
        RetryOutcome<String> out = executor.executeRead(call);
        log.info("请求输入=读操作首次连接失败、随后成功；判定依据：CONNECTION_FAILURE 可重试；结果 success={} attempts={} value={}",
                out.success(), out.attempts(), out.value());
        assertTrue(out.success());
        assertEquals(2, out.attempts());
        assertEquals("ok", out.value());
    }

    @Test
    void writeTimeoutIsNeverRetriedBecauseEffectUnknown() {
        SafeRetryExecutor executor = executor(3, 2);
        AtomicInteger calls = new AtomicInteger();
        DownstreamCall<String> call = () -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException("timeout after write sent");
        };
        RetryOutcome<String> out = executor.executeWrite(call, "key-timeout");
        log.info("请求输入=写操作超时 key=key-timeout；判定依据：写超时效果未知禁止重试；结果 success={} errorType={} attempts={} actualCalls={}",
                out.success(), out.errorType(), out.attempts(), calls.get());
        assertFalse(out.success());
        assertEquals(ErrorType.TIMEOUT, out.errorType());
        assertEquals(1, calls.get(), "写超时绝不能重试");
    }

    @Test
    void businessRejectionIsNotRetried() {
        SafeRetryExecutor executor = executor(3, 2);
        AtomicInteger calls = new AtomicInteger();
        DownstreamCall<String> call = () -> {
            calls.incrementAndGet();
            throw new BusinessRejectionException("余额不足，业务拒绝");
        };
        RetryOutcome<String> out = executor.executeRead(call);
        log.info("请求输入=业务拒绝；判定依据：BUSINESS_REJECTION 结果确定不重试；结果 errorType={} attempts={} message={}",
                out.errorType(), out.attempts(), out.safeMessage());
        assertFalse(out.success());
        assertEquals(ErrorType.BUSINESS_REJECTION, out.errorType());
        assertEquals(1, calls.get());
    }

    @Test
    void unexpectedExceptionMapsToInternalWithoutLeak() {
        SafeRetryExecutor executor = executor(3, 2);
        DownstreamCall<String> call = () -> {
            throw new IllegalStateException("com.internal.SecretClient NPE detail");
        };
        RetryOutcome<String> out = executor.executeRead(call);
        log.info("请求输入=未预期异常；判定依据：归一化为 INTERNAL 且不重试；结果 errorType={} message={}",
                out.errorType(), out.safeMessage());
        assertFalse(out.success());
        assertEquals(ErrorType.INTERNAL, out.errorType());
        assertFalse(out.safeMessage().contains("SecretClient"));
    }

    @Test
    void writeRetriesConnectionFailureButExecutesOnce() {
        SafeRetryExecutor executor = executor(3, 2);
        AtomicInteger calls = new AtomicInteger();
        DownstreamCall<String> call = () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ConnectException("refused");
            }
            return "created";
        };
        RetryOutcome<String> out = executor.executeWrite(call, "order-1");
        log.info("请求输入=写操作首次连接失败 key=order-1；判定依据：连接失败=未送达，可在幂等执行权内重试一次；结果 success={} attempts={} actualCalls={}",
                out.success(), out.attempts(), calls.get());
        assertTrue(out.success());
        assertEquals(2, out.attempts());
        assertEquals(2, calls.get());
    }

    /**
     * 同一幂等键的并发写调用：底层"生效"只能发生一次，所有调用方拿到同一结果。
     */
    @Test
    void concurrentDuplicateWritesExecuteEffectOnce() throws Exception {
        SafeRetryExecutor executor = executor(3, 2);
        AtomicInteger effects = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DownstreamCall<String> call = () -> {
            effects.incrementAndGet();
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "created-once";
        };

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<RetryOutcome<String>> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    RetryOutcome<String> out = executor.executeWrite(call, "dedup-key");
                    outcomes.add(out);
                    log.info("请求输入[{}] 写操作 key=dedup-key 并发到达；判定依据：同键折叠到唯一执行权；结果 success={} value={}",
                            idx, out.success(), out.value());
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS), "应有且仅有一次实际执行进入");
        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        log.info("最终结果 actualEffects={} outcomes={}", effects.get(), outcomes.size());
        assertEquals(1, effects.get(), "并发重复写只能生效一次");
        assertEquals(threads, outcomes.size());
        assertTrue(outcomes.stream().allMatch(o -> o.success() && "created-once".equals(o.value())));
    }

    /**
     * 同键的重复写调用（即使发生在首次成功之后）直接复用终态，不再次生效。
     */
    @Test
    void repeatedWriteWithSameKeyReplaysCachedResult() {
        SafeRetryExecutor executor = executor(3, 2);
        AtomicInteger effects = new AtomicInteger();
        DownstreamCall<String> call = () -> "created#" + effects.incrementAndGet();

        RetryOutcome<String> first = executor.executeWrite(call, "replay-key");
        RetryOutcome<String> second = executor.executeWrite(call, "replay-key");
        log.info("请求输入=同键写两次 replay-key；判定依据：终态结果缓存复用；结果 first={} second={} actualEffects={}",
                first.value(), second.value(), effects.get());
        assertEquals("created#1", first.value());
        assertEquals("created#1", second.value());
        assertEquals(1, effects.get(), "重复写不得二次生效");
    }
}
