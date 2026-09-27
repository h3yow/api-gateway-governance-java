package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.ScopeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高并发下的一致性：
 * <ul>
 *   <li>放行数绝不超过硬上限/桶容量，累计计数与每个结论对得上；</li>
 *   <li>所有结论里的窗口剩余 ∈ [0,limit]，绝不出现"还剩5却被拒"或"用尽仍放行"；</li>
 *   <li>放行结论的剩余必须严格小于上限（确实占用了 1），拒绝结论被打满口径剩余为 0；</li>
 *   <li>并发只读快照过程中，用量始终不越界，且快照本身不改变计数。</li>
 * </ul>
 */
class QuotaConsistencyConcurrencyTest {

    private RateLimitService windowOnlyService(long quota) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule d = new RateLimitProperties.Rule();
        d.setWindowQuota(quota);
        d.setWindowMillis(60_000L);
        d.setBurstCapacity(-1);
        props.setDefaults(d);
        return new RateLimitService(props);
    }

    @Test
    void windowQuotaConcurrencyNeverOverdrawsAndCountersMatch() throws Exception {
        long quota = 1_000;
        int threads = 64;
        int perThread = 30; // 总请求 1920 > 1000
        RateLimitService service = windowOnlyService(quota);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<RateLimitDecision> decisions = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        RateLimitDecision d = service.check("caller-x", "route-y");
                        decisions.add(d);
                        if (d.allowed()) {
                            allowed.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        int total = threads * perThread;
        assertEquals(total, allowed.get() + rejected.get());

        // 逐结论自洽性检查：放行剩余必须 ∈[0,quota-1]，且 used=quota-remaining；
        // 拒绝剩余必须为 0（不能"还剩却被拒"）。
        for (RateLimitDecision d : decisions) {
            var w = d.quota().route().window();
            assertTrue(w.remaining() >= 0L && w.remaining() <= quota, "剩余不得越界");
            assertEquals(w.limit() - w.remaining(), w.used(), "used + remaining = limit");
            if (d.allowed()) {
                assertTrue(w.remaining() < quota, "放行结论必须体现本次占用");
                assertEquals(0L, d.retryAfterMillis());
            } else {
                assertEquals(0L, w.remaining(), "窗口口径被拒时剩余必须为0");
                assertTrue(d.retryAfterMillis() > 0L, "被拒必须带正等待时间");
            }
        }

        ScopeSnapshot route = service.snapshot().routes().get("route-y");
        ScopeSnapshot caller = service.snapshot().callers().get("caller-x");
        // 前 quota 个请求两维度都通过；之后全部在路由窗口短路。
        assertEquals(quota, route.totalAllowed(), "路由放行恰好=配额，绝不超额");
        assertEquals(total - quota, route.totalRejected());
        assertEquals(quota, caller.totalAllowed(), "调用方只在前quota个请求参与并通过");
        assertEquals(0L, caller.totalRejected());
        assertEquals(quota, route.window().used(), "快照用量=硬上限，未超额");
        assertEquals(0L, route.window().remaining());

        TestLogger.log("并发额度一致性(窗口)",
                "threads=64 x30=1920 请求, 窗口配额=1000",
                "每结论 used+remaining=limit；拒绝剩余0；计数逐请求可对帐",
                "allowed=" + allowed.get() + " rejected=" + rejected.get()
                        + " routeUsed=" + route.window().used());
    }

    @Test
    void snapshotReadersNeverObserveTornOrOverLimitState() throws Exception {
        long quota = 500;
        RateLimitService service = windowOnlyService(quota);
        int writers = 32;
        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + readers);
        AtomicInteger violations = new AtomicInteger();

        for (int i = 0; i < writers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 40; k++) {
                        service.check("c", "r");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 200; k++) {
                        ScopeSnapshot s = service.snapshot().routes().get("r");
                        if (s != null) {
                            if (s.window().used() < 0 || s.window().used() > quota
                                    || s.window().remaining() < 0
                                    || s.totalAllowed() + s.totalRejected() < s.totalAllowed()) {
                                violations.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, violations.get(), "任何快照都不得出现越界或撕裂值");
        ScopeSnapshot route = service.snapshot().routes().get("r");
        assertEquals(quota, route.window().used());
        assertEquals(quota, route.totalAllowed());
        TestLogger.log("快照并发只读",
                "32 写线程打 500 配额 + 8 读线程各 200 次快照",
                "快照只读不计数，且 used 恒在 [0,quota]",
                "violations=0, route.used=" + route.window().used());
    }
}
