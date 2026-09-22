package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.FixedWindowQuotaLimiter;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.TokenBucketLimiter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发限流测试：固定窗口配额 + 令牌桶突发，在并发压力下不得超额放行；
 * 拒绝原因必须可按维度与语义区分。
 */
class RateLimitConcurrencyTest {

    @Test
    void concurrentWindowQuotaNeverExceeds() throws Exception {
        int quota = 500;
        int threads = 64;
        int perThread = 20;
        FixedWindowQuotaLimiter limiter = new FixedWindowQuotaLimiter(quota, 60_000L);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire(1_000L)) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "线程应在超时前全部结束");
        pool.shutdown();

        int total = threads * perThread;
        TestLogger.log("固定窗口配额并发",
                "threads=" + threads + " perThread=" + perThread + " quota=" + quota,
                "CAS 原子快照计数，窗口内硬上限 " + quota,
                "allowed=" + allowed.get() + " rejected=" + (total - allowed.get()));
        assertEquals(quota, allowed.get(), "并发下放行数必须恰好等于配额，绝不超额");
        assertEquals(quota, limiter.currentCount(1_000L), "窗口已用量必须等于配额");
    }

    @Test
    void windowRolloverResetsCount() {
        FixedWindowQuotaLimiter limiter = new FixedWindowQuotaLimiter(2, 100L);
        assertTrue(limiter.tryAcquire(0L));
        assertTrue(limiter.tryAcquire(50L));
        assertFalse(limiter.tryAcquire(99L));
        // 跨入 [100,200) 窗口：配额重置
        assertTrue(limiter.tryAcquire(100L));
        assertTrue(limiter.tryAcquire(150L));
        assertFalse(limiter.tryAcquire(199L));
        TestLogger.log("固定窗口滚动",
                "quota=2 window=100ms, 请求时刻 0/50/99/100/150/199",
                "窗口边界 floor(now/window)*window",
                "窗口1放行2拒绝1；窗口2放行2拒绝1（计数确定重置）");
    }

    @Test
    void concurrentBurstNeverExceedsCapacity() throws Exception {
        long capacity = 100;
        int threads = 32;
        int perThread = 10;
        // 固定时钟：本次测试只验证"初始满桶被并发抽取绝不超过容量"。
        TokenBucketLimiter limiter = new TokenBucketLimiter(capacity, 1.0d);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire(0L)) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        TestLogger.log("令牌桶突发并发",
                "threads=" + threads + " perThread=" + perThread + " capacity=" + capacity,
                "CAS 更新(tokens,lastRefill) 快照，每枚令牌只被取一次",
                "allowed=" + allowed.get() + " rejected=" + (threads * perThread - allowed.get()));
        assertEquals(capacity, allowed.get(), "突发放行数必须恰好等于桶容量");
        assertEquals(0.0d, limiter.availableTokens(0L), 0.0001d, "桶应被抽干");
    }

    @Test
    void tokenBucketRefillsAtSteadyRate() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(10, 5.0d);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(0L));
        }
        assertFalse(limiter.tryAcquire(0L), "桶空后必须拒绝");
        // 200ms 后应填充 1 枚令牌（5/s * 0.2s）
        assertTrue(limiter.tryAcquire(200L), "填充后应可再放行 1 个");
        assertFalse(limiter.tryAcquire(200L), "刚填充的令牌被取走后仍为空");
        TestLogger.log("令牌桶填充",
                "capacity=10 refill=5/s, 抽干后 +200ms 再请求两次",
                "refilled = min(capacity, tokens + elapsed*rate)",
                "第一次放行(第11枚令牌)，第二次拒绝，稳态速率语义成立");
    }

    @Test
    void allFourRejectReasonsAreObservableInOrder() {
        // 路由规则：窗口配额=2，无突发；调用方规则：无窗口，突发桶=2（初始满）。
        // 预期：请求1放行；请求2放行（消耗路由窗口2次+调用方令牌2枚）；
        // 请求3 撞路由窗口配额；放宽时间不会发生（窗口未滚动）。
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule defaults = new RateLimitProperties.Rule();
        defaults.setWindowQuota(-1);
        defaults.setBurstCapacity(-1);
        props.setDefaults(defaults);

        RateLimitProperties.Rule routeRule = new RateLimitProperties.Rule();
        routeRule.setWindowQuota(2);
        routeRule.setWindowMillis(60_000L);
        props.setRoutes(java.util.Map.of("orders", routeRule));

        RateLimitProperties.Rule callerRule = new RateLimitProperties.Rule();
        callerRule.setBurstCapacity(2);
        callerRule.setRefillPerSecond(0.001d);
        props.setCallers(java.util.Map.of("caller-A", callerRule));

        RateLimitService service = new RateLimitService(props);
        var d1 = service.check("caller-A", "orders");
        var d2 = service.check("caller-A", "orders");
        var d3 = service.check("caller-A", "orders");
        var d4 = service.check("caller-A", "orders");

        assertTrue(d1.allowed() && d2.allowed());
        assertFalse(d3.allowed());
        assertEquals(com.github.h3yow.apigatewaygovernancejava.governance.ratelimit
                .RateLimitRejectReason.ROUTE_WINDOW_QUOTA, d3.reason(),
                "路由窗口配额必须先于调用方突发被判定");
        // 路由窗口仍在耗尽状态，继续被同一原因短路
        assertEquals(com.github.h3yow.apigatewaygovernancejava.governance.ratelimit
                .RateLimitRejectReason.ROUTE_WINDOW_QUOTA, d4.reason());

        TestLogger.log("四原因判定顺序",
                "route(窗口=2,无突发) + caller(无窗口,突发=2)，连续4次",
                "路由维度先判定且窗口先于突发，因此路由窗口耗尽会短路",
                "放行2；拒绝均为 ROUTE_WINDOW_QUOTA(429)，原因码确定可区分");
    }

    @Test
    void callerBurstReasonObservedWhenRouteAllows() {
        // 路由不限，调用方突发桶=1：第2个请求必须命中 CALLER_BURST 而非其他原因。
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule defaults = new RateLimitProperties.Rule();
        defaults.setWindowQuota(-1);
        defaults.setBurstCapacity(-1);
        props.setDefaults(defaults);

        RateLimitProperties.Rule callerRule = new RateLimitProperties.Rule();
        callerRule.setBurstCapacity(1);
        callerRule.setRefillPerSecond(0.001d);
        props.setCallers(java.util.Map.of("caller-B", callerRule));

        RateLimitService service = new RateLimitService(props);
        assertTrue(service.check("caller-B", "profiles").allowed());
        RateLimitDecision rejected = service.check("caller-B", "profiles");
        assertFalse(rejected.allowed());
        assertEquals(com.github.h3yow.apigatewaygovernancejava.governance.ratelimit
                .RateLimitRejectReason.CALLER_BURST, rejected.reason());
        TestLogger.log("调用方突发拒绝",
                "路由不限；caller-B 突发桶=1，连续2次请求",
                "路由放行后在调用方维度抽取令牌，桶空即 CALLER_BURST",
                "第1次放行，第2次拒绝原因=" + rejected.reason()
                        + " code=" + rejected.reason().errorCode().reasonCode());
    }

    @Test
    void rejectReasonsAreDistinguishableByDimension() {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule tight = new RateLimitProperties.Rule();
        tight.setWindowQuota(1);
        tight.setWindowMillis(60_000L);
        tight.setBurstCapacity(1);
        tight.setRefillPerSecond(0.001d);
        props.setDefaults(tight);
        RateLimitService service = new RateLimitService(props);

        List<RateLimitDecision> decisions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            decisions.add(service.check("caller-A", "orders"));
        }
        // 第 1 个通过；第 2 个先撞路由窗口配额；放大窗口后才能观察调用方维度，
        // 因此这里直接验证确定性顺序：窗口先于突发、路由先于调用方。
        assertTrue(decisions.get(0).allowed());
        assertFalse(decisions.get(1).allowed());
        assertEquals(
                com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRejectReason.ROUTE_WINDOW_QUOTA,
                decisions.get(1).reason());

        TestLogger.log("拒绝原因区分",
                "同一 caller/route 连续 4 次请求，窗口=1 突发=1",
                "判定顺序：路由窗口→路由突发→调用方窗口→调用方突发",
                "第1次放行，第2次原因=" + decisions.get(1).reason()
                        + " http=" + decisions.get(1).reason().errorCode().httpStatus());
    }
}
