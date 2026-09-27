package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观测能力的并发正确性测试：
 * <ul>
 *   <li>高并发下放行结论与剩余额度必须严格一致：放行总数恰好 = 配额，拒绝结论剩余恒为 0，
 *       绝不出现负数或“有余量却被拒”；</li>
 *   <li>并发读取快照不会读到撕裂状态：used ≤ quota、remaining ≥ 0、used+remaining = quota，
 *       且查询不占额度、不改计数。</li>
 * </ul>
 */
class RateLimitSnapshotConcurrencyTest {

    @Test
    void concurrentWindowDecisionsNeverContradictState() throws Exception {
        RateLimitService service = serviceWith(500, 60_000L);
        service.setClockForTests(() -> 1_000L); // 固定时刻：不发生窗口滚动
        int threads = 64;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Boolean> contradiction = new AtomicReference<>(false);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        RateLimitDecision d = service.check("caller-C", "orders");
                        if (d.allowed()) {
                            allowed.incrementAndGet();
                            // 自洽：放行结论的两个窗口剩余都必须 >= 0
                            if (d.route().windowRemaining() < 0 || d.caller().windowRemaining() < 0) {
                                contradiction.set(true);
                            }
                        } else {
                            rejected.incrementAndGet();
                            // 自洽：被拒绝时，被拒口径剩余必须恰为 0，不能“还剩 5 却被拒”
                            long remaining = d.reason().name().contains("ROUTE")
                                    ? d.route().windowRemaining() : d.caller().windowRemaining();
                            if (remaining != 0L) {
                                contradiction.set(true);
                            }
                            if (d.retryAfterMillis() <= 0L) {
                                contradiction.set(true);
                            }
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

        int total = threads * perThread;
        RateLimitSnapshot snap = service.snapshot();
        RateLimitSnapshot.DimensionSnapshot routeSnap = snap.routes().get("orders");
        RateLimitSnapshot.DimensionSnapshot callerSnap = snap.callers().get("caller-C");

        TestLogger.log("并发结论一致性",
                "threads=" + threads + " perThread=" + perThread + " quota=500(route&caller)",
                "每个结论的剩余额度与 CAS 计数同源",
                "allowed=" + allowed.get() + " rejected=" + rejected.get()
                        + " route.used=" + routeSnap.window().used()
                        + " caller.used=" + callerSnap.window().used());

        assertEquals(500, allowed.get(), "放行必须恰好等于较小维度的硬配额 500");
        assertEquals(total - 500, rejected.get());
        assertFalse(contradiction.get(), "不得出现剩余为负 / 有余量被拒 / 无等待时间等矛盾");
        assertEquals(500L, routeSnap.window().used());
        assertEquals(0L, routeSnap.window().remaining());
        assertEquals(500L, callerSnap.window().used());
        assertEquals(0L, callerSnap.window().remaining());
        assertEquals(500L, routeSnap.allowedTotal());
        // 路由维度先判：全部 1280 次都被路由计数（500 放行 + 780 拒绝）
        assertEquals(780L, routeSnap.rejectedTotal());
        // 调用方维度：只有通过路由的 500 次到达，且全部通过（配额同为 500）
        assertEquals(500L, callerSnap.allowedTotal());
        assertEquals(0L, callerSnap.rejectedTotal());
    }

    @Test
    void concurrentSnapshotsAreAlwaysInternallyConsistent() throws Exception {
        RateLimitService service = serviceWith(200, 60_000L);
        service.setClockForTests(() -> 1_000L);
        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writersDone = new AtomicInteger();
        AtomicReference<Boolean> torn = new AtomicReference<>(false);
        int writerThreads = 16;
        int requestsPerWriter = 30;

        for (int t = 0; t < writerThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < requestsPerWriter; i++) {
                        service.check("caller-S", "orders");
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.incrementAndGet();
                }
            });
        }
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writersDone.get() < writerThreads) {
                        RateLimitSnapshot snap = service.snapshot();
                        for (RateLimitSnapshot.DimensionSnapshot dim :
                                new RateLimitSnapshot.DimensionSnapshot[]{
                                        snap.routes().get("orders"),
                                        snap.callers().get("caller-S")}) {
                            RateLimitSnapshot.WindowSnapshot w = dim.window();
                            if (w.used() < 0 || w.remaining() < 0 || w.used() > w.quota()
                                    || w.used() + w.remaining() != w.quota()) {
                                torn.set(true);
                            }
                            RateLimitSnapshot.BurstSnapshot b = dim.burst();
                            if (b.usedTokens() < -1e-9 || b.availableTokens() < -1e-9
                                    || b.availableTokens() > b.capacity() + 1e-9) {
                                torn.set(true);
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertFalse(torn.get(), "并发下快照必须始终内部一致：used<=quota、remaining>=0、和为配额");
        TestLogger.log("快照并发一致",
                "16 写线程 × 30 请求 + 4 读线程持续快照",
                "快照为同一时刻单不可变状态投影，纯读无 CAS",
                "无撕裂/超额/负余量，torn=false");
    }

    @Test
    void snapshotDoesNotConsumeQuotaOrCounters() {
        RateLimitService service = serviceWith(10, 60_000L);
        service.setClockForTests(() -> 1_000L);
        service.check("caller-Q", "orders");
        RateLimitSnapshot before = service.snapshot();
        for (int i = 0; i < 5; i++) {
            service.snapshot();
        }
        RateLimitSnapshot after = service.snapshot();
        assertEquals(before.routes().get("orders").window().used(),
                after.routes().get("orders").window().used(), "只读快照不得改变窗口用量");
        assertEquals(before.routes().get("orders").allowedTotal(),
                after.routes().get("orders").allowedTotal(), "只读快照不得改变累计计数");
        assertEquals(1L, after.routes().get("orders").window().used());
        assertEquals(9L, after.routes().get("orders").window().remaining());
        assertEquals(1L, after.routes().get("orders").allowedTotal());
        TestLogger.log("快照零副作用",
                "1 次请求后连续 6 次快照",
                "snapshot() 不调用任何占用/CAS",
                "used 恒为 1，allowedTotal 恒为 1");
    }

    @Test
    void snapshotAcrossWindowRolloverShowsFreshWindow() {
        long[] now = {0L};
        RateLimitService service = serviceWith(2, 100L);
        service.setClockForTests(() -> now[0]);
        service.check("caller-R", "orders");
        service.check("caller-R", "orders"); // 用满窗口 1
        RateLimitSnapshot exhausted = service.snapshot();
        assertEquals(2L, exhausted.routes().get("orders").window().used());
        // 推进到下一窗口；快照（纯读）应看到新窗口 0 已用
        now[0] = 100L;
        RateLimitSnapshot fresh = service.snapshot();
        RateLimitSnapshot.WindowSnapshot w = fresh.routes().get("orders").window();
        assertEquals(0L, w.used());
        assertEquals(2L, w.remaining());
        assertEquals(100L, w.windowStart());
        assertEquals(200L, w.windowEnd());
        // 累计计数不因滚动丢失
        assertEquals(2L, fresh.routes().get("orders").allowedTotal());
        TestLogger.log("快照跨窗口",
                "窗口=2 用满后推进到 100ms 再读快照",
                "快照按当前窗口起点投影，滚动后 0 已用，累计计数保留",
                "used 2->0, remaining 0->2, allowedTotal=2");
    }

    private RateLimitService serviceWith(long quota, long windowMillis) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule defaults = new RateLimitProperties.Rule();
        defaults.setWindowQuota(-1);
        defaults.setBurstCapacity(-1);
        props.setDefaults(defaults);
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setWindowQuota(quota);
        rule.setWindowMillis(windowMillis);
        // 并发一致性测试同样开启突发桶，顺带验证 burst 快照不超额
        rule.setBurstCapacity(quota);
        rule.setRefillPerSecond(0.001d);
        props.setRoutes(java.util.Map.of("orders", rule));
        RateLimitProperties.Rule callerRule = new RateLimitProperties.Rule();
        callerRule.setWindowQuota(quota);
        callerRule.setWindowMillis(windowMillis);
        callerRule.setBurstCapacity(quota);
        callerRule.setRefillPerSecond(0.001d);
        props.setCallers(java.util.Map.of(
                "caller-C", callerRule, "caller-S", callerRule,
                "caller-Q", callerRule, "caller-R", callerRule));
        return new RateLimitService(props);
    }
}
