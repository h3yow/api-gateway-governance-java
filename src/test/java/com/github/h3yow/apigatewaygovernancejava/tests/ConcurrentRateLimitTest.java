package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.ratelimit.RateLimitPolicy;
import com.github.h3yow.apigatewaygovernancejava.ratelimit.RateLimiter;
import com.github.h3yow.apigatewaygovernancejava.ratelimit.RejectReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发限流测试：高并发下不得超额放行，拒绝原因可区分。
 * 每个用例都打印请求输入、判定依据与最终结果。
 */
class ConcurrentRateLimitTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRateLimitTest.class);

    /**
     * 调用方策略：每 1 秒窗口 5 个请求、无突发；
     * 100 个线程同时请求同一 caller+route，放行数必须恰好为 5。
     */
    @Test
    void noOverAdmissionUnderConcurrency_callerDimension() throws Exception {
        RateLimiter limiter = new RateLimiter(
                new RateLimitPolicy(5, Duration.ofSeconds(1), 0),
                new RateLimitPolicy(1000, Duration.ofSeconds(1), 0));

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Map<RejectReason, Integer> reasons = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    RateLimitDecision d = limiter.check("caller-A", "route-orders");
                    log.info("请求输入[{}] caller=caller-A route=route-orders；判定依据：调用方窗口5/秒无突发；结果 allowed={} reason={} remaining={} retryAfter={}ms",
                            idx, d.allowed(), d.reason(), d.remainingTokens(), d.retryAfterMillis());
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                        reasons.merge(d.reason(), 1, Integer::sum);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        log.info("最终结果 allowed={} rejected={} reasons={}", allowed.get(), rejected.get(), reasons);
        assertEquals(5, allowed.get(), "并发下超额放行");
        assertEquals(threads - 5, rejected.get());
        assertEquals(threads - 5, reasons.get(RejectReason.CALLER_QUOTA_EXHAUSTED),
                "所有拒绝必须可归因为调用方配额耗尽");
    }

    /**
     * 调用方额度充足，路由维度成为瓶颈：
     * 拒绝原因必须是路由容量耗尽，而不是调用方原因。
     */
    @Test
    void rejectionDistinguishesRouteDimension() throws Exception {
        RateLimiter limiter = new RateLimiter(
                new RateLimitPolicy(1000, Duration.ofSeconds(1), 0),
                new RateLimitPolicy(3, Duration.ofSeconds(1), 0));

        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        Map<RejectReason, Integer> reasons = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    RateLimitDecision d = limiter.check("caller-" + idx, "route-hot");
                    log.info("请求输入[{}] caller=caller-{} route=route-hot；判定依据：路由窗口3/秒，调用方额度充足；结果 allowed={} reason={}",
                            idx, idx, d.allowed(), d.reason());
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        reasons.merge(d.reason(), 1, Integer::sum);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        log.info("最终结果 allowed={} reasons={}", allowed.get(), reasons);
        assertEquals(3, allowed.get());
        assertEquals(threads - 3, reasons.get(RejectReason.ROUTE_CAPACITY_EXHAUSTED),
                "瓶颈在路由维度时必须返回路由拒绝原因");
        assertNull(reasons.get(RejectReason.CALLER_QUOTA_EXHAUSTED));
    }

    /**
     * 窗口补充语义：耗尽后等待一个窗口，令牌应重新可用，状态不错算、不丢失。
     */
    @Test
    void windowRefillRestoresTokens() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(
                new RateLimitPolicy(2, Duration.ofMillis(100), 0),
                new RateLimitPolicy(1000, Duration.ofSeconds(1), 0));

        for (int i = 0; i < 2; i++) {
            assertTrue(limiter.check("caller-R", "route").allowed());
        }
        RateLimitDecision blocked = limiter.check("caller-R", "route");
        log.info("请求输入 caller=caller-R；判定依据：2/100ms 窗口已耗尽；结果 allowed={} reason={}",
                blocked.allowed(), blocked.reason());
        assertFalse(blocked.allowed());
        assertEquals(RejectReason.CALLER_QUOTA_EXHAUSTED, blocked.reason());

        Thread.sleep(150);
        RateLimitDecision restored = limiter.check("caller-R", "route");
        log.info("请求输入 caller=caller-R；判定依据：已等待超过一个窗口；结果 allowed={} remaining={}",
                restored.allowed(), restored.remainingTokens());
        assertTrue(restored.allowed(), "窗口滑动后令牌应恢复");
    }
}
