package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRejectReason;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两维度 × 两口径“结论同体”修复验证：
 * 不管请求先被路由维度还是调用方维度拒掉，成功与 429 结论都必须同时带齐
 * 路由、调用方两个维度在固定窗口与突发容量下的剩余额度；未实际扣减的一侧只做
 * 同一时刻的只读展示，不占额度、不改计数、不改拒绝原因与顶层等待时间。
 *
 * <p>覆盖四种拒绝：路由固定窗口、路由突发、调用方固定窗口、调用方突发，
 * 并断言被拒口径不出现正余量、只读维度反映当时真实余量。
 */
class RateLimitBothDimensionsReportTest {

    // ---------- 场景 1：路由固定窗口拒绝 ----------

    @Test
    void routeWindowRejectStillCarriesCallerWindowAndBurstReadOnly() {
        // 路由窗口=2（更紧），调用方窗口=5、突发=5：第 3 个请求必撞路由窗口。
        RateLimitService service = serviceWith(
                rule(2, 60_000L, 5, 1.0d),
                rule(5, 60_000L, 5, 1.0d));
        service.setClockForTests(() -> 1_000L);

        RateLimitDecision d1 = service.check("caller-RW", "orders");
        RateLimitDecision d2 = service.check("caller-RW", "orders");
        RateLimitDecision rejected = service.check("caller-RW", "orders");

        assertTrue(d1.allowed() && d2.allowed());
        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, rejected.reason(),
                "更紧的路由窗口必须先挡住，原因不可被误归为调用方");

        // 被拒口径（路由窗口）：余量恒为 0，不出现正余量；等待为距窗口结束的真实时间
        assertNotNull(rejected.route());
        assertEquals(0L, rejected.route().windowRemaining());
        assertEquals(59_000L, rejected.route().windowRetryAfterMillis(),
                "60000ms 窗口在 now=1000 时剩余 59000ms");
        assertTrue(rejected.retryAfterMillis() == rejected.route().windowRetryAfterMillis(),
                "顶层等待必须只取实际被拒口径（路由窗口）的等待");
        // 同维度突发口径未短路前的状态：前2次请求各取 1 枚 -> 5-2=3
        assertEquals(3.0d, rejected.route().burstRemaining(), 1e-9);

        // 调用方维度本次被短路（不判定/不占额度），但真实余量只读带回：窗口 5-2=3、突发 5-2=3
        assertBothCalibersPresent(rejected.caller());
        assertEquals(3L, rejected.caller().windowRemaining());
        assertEquals(3.0d, rejected.caller().burstRemaining(), 1e-9);
        assertEquals(0L, rejected.caller().windowRetryAfterMillis(),
                "只读维度有余量时其等待字段为 0，且绝不参与顶层等待");

        TestLogger.log("路由窗口拒绝-两维同体",
                "route窗口2/突发5，caller窗口5/突发5，连续3次",
                "路由窗口先拒；调用方不扣额度，只读展示真实余量",
                "route.window=0 route.burst=3 caller.window=3 caller.burst=3(只读)");
    }

    // ---------- 场景 2：路由突发拒绝 ----------

    @Test
    void routeBurstRejectStillCarriesCallerWindowAndBurstReadOnly() {
        // 窗口都很宽；路由突发=1（最紧），调用方窗口=100、突发=100：第 2 个请求撞路由突发。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 1, 1.0d),
                rule(100, 60_000L, 100, 1.0d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-RB", "orders").allowed());
        RateLimitDecision rejected = service.check("caller-RB", "orders");

        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_BURST, rejected.reason(),
                "必须归为路由突发限流，不能误归为调用方错误或服务端故障");
        // 路由：突发被拒（不足 1 枚，时钟未推进 -> 0），但窗口只真实用了 2 次，剩 98
        assertEquals(0.0d, rejected.route().burstRemaining(), 1e-9);
        assertTrue(rejected.route().burstRemaining() < 1.0d, "被拒突发口径不得出现正余量");
        assertEquals(98L, rejected.route().windowRemaining());
        assertEquals(rejected.route().burstRetryAfterMillis(), rejected.retryAfterMillis(),
                "顶层等待只取路由突发口径");
        // 调用方维度只读：首请求后窗口 99、突发 99
        assertBothCalibersPresent(rejected.caller());
        assertEquals(99L, rejected.caller().windowRemaining());
        assertEquals(99.0d, rejected.caller().burstRemaining(), 1e-9);

        TestLogger.log("路由突发拒绝-两维同体",
                "route窗口100/突发1，caller窗口100/突发100，连续2次",
                "路由突发先拒；调用方只读展示，不抽令牌",
                "route.burst=0 route.window=98 caller.window=99 caller.burst=99(只读)");
    }

    // ---------- 场景 3：调用方固定窗口拒绝 ----------

    @Test
    void callerWindowRejectCarriesConsumedRouteAndZeroCallerCalibers() {
        // 路由窗口=100/突发=100（宽），调用方窗口=2（紧）：第 3 个请求撞调用方窗口。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 100, 1.0d),
                rule(2, 60_000L, 100, 1.0d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-CW", "orders").allowed());
        assertTrue(service.check("caller-CW", "orders").allowed());
        RateLimitDecision rejected = service.check("caller-CW", "orders");

        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.CALLER_WINDOW_QUOTA, rejected.reason());
        // 调用方被拒口径：窗口余量 0；同维度突发口径只读补齐（时钟固定，前2次取 2 枚 -> 98）
        assertBothCalibersPresent(rejected.caller());
        assertEquals(0L, rejected.caller().windowRemaining());
        assertEquals(59_000L, rejected.caller().windowRetryAfterMillis());
        assertEquals(98.0d, rejected.caller().burstRemaining(), 1e-9);
        assertEquals(rejected.caller().windowRetryAfterMillis(), rejected.retryAfterMillis());
        // 路由维度本次先通过并真实占用：3 次 -> 窗口 97、突发 97
        assertBothCalibersPresent(rejected.route());
        assertEquals(97L, rejected.route().windowRemaining());
        assertEquals(97.0d, rejected.route().burstRemaining(), 1e-9);

        TestLogger.log("调用方窗口拒绝-两维同体",
                "route100/100，caller窗口2/突发100，连续3次",
                "路由真实占用后调用方窗口拒；同维度突发只读，路由余量带回",
                "caller.window=0 caller.burst=98 route.window=97 route.burst=97");
    }

    // ---------- 场景 4：调用方突发拒绝 ----------

    @Test
    void callerBurstRejectCarriesConsumedRouteAndSeparatedCallerWindow() {
        // 路由宽；调用方窗口=100、突发=1：第 2 个请求撞调用方突发，窗口仍有 98。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 100, 1.0d),
                rule(100, 60_000L, 1, 1.0d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-CB", "orders").allowed());
        RateLimitDecision rejected = service.check("caller-CB", "orders");

        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.CALLER_BURST, rejected.reason());
        // 调用方：突发余量 0（<1 即被拒，不得为正余量），窗口仍剩 98，两口径不互相冒充
        assertBothCalibersPresent(rejected.caller());
        assertEquals(0.0d, rejected.caller().burstRemaining(), 1e-9);
        assertEquals(98L, rejected.caller().windowRemaining());
        assertEquals(rejected.caller().burstRetryAfterMillis(), rejected.retryAfterMillis());
        assertTrue(rejected.caller().burstRetryAfterMillis() >= 1_000L,
                "1/s 回补 1 枚令牌需 1000ms");
        // 路由：两次真实占用 -> 窗口 98、突发 98
        assertBothCalibersPresent(rejected.route());
        assertEquals(98L, rejected.route().windowRemaining());
        assertEquals(98.0d, rejected.route().burstRemaining(), 1e-9);

        TestLogger.log("调用方突发拒绝-两维同体",
                "route100/100，caller窗口100/突发1，连续2次",
                "调用方突发拒；其窗口口径余额独立报告，路由已占用余量带回",
                "caller.burst=0 caller.window=98 route.window=98 route.burst=98");
    }

    /** 两口径齐全断言：启用了窗口+突发的维度，四个字段都不得缺失。 */
    private void assertBothCalibersPresent(RateLimitDecision.DimensionQuota q) {
        assertNotNull(q, "维度配额对象必须出现");
        assertNotNull(q.windowRemaining(), "固定窗口口径剩余必须带齐");
        assertNotNull(q.windowRetryAfterMillis(), "固定窗口等待字段必须带齐");
        assertNotNull(q.burstRemaining(), "突发口径剩余必须带齐");
        assertNotNull(q.burstRetryAfterMillis(), "突发等待字段必须带齐");
        assertTrue(q.windowRemaining() >= 0L, "窗口剩余不得为负");
        assertTrue(q.burstRemaining() >= 0.0d, "突发剩余不得为负");
    }

    // ---------- 边界时刻：窗口滚动 / 令牌回补 ----------

    @Test
    void readOnlyProjectionFollowsWindowRolloverAtBoundary() {
        long[] now = {0L};
        // 路由窗口=1/窗口100ms，调用方窗口=1/窗口100ms；均无突发。
        RateLimitService service = serviceWith(
                rule(1, 100L, -1, -1),
                rule(1, 100L, -1, -1));
        service.setClockForTests(() -> now[0]);

        assertTrue(service.check("caller-RW", "orders").allowed());
        RateLimitDecision rejectedBefore = service.check("caller-RW", "orders");
        assertFalse(rejectedBefore.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, rejectedBefore.reason());
        // 99ms：路由窗口真实耗尽，等待 1ms；调用方维度只读，也恰耗尽 -> 其自身真实等待 1ms
        now[0] = 99L;
        // 重新取一次结论（首请求发生在 0ms；这里在 99ms 再被路由挡）
        RateLimitDecision at99 = service.check("caller-RW", "orders");
        assertEquals(0L, at99.route().windowRemaining());
        assertEquals(1L, at99.route().windowRetryAfterMillis(),
                "99ms 时距窗口结束恰为 1ms");
        assertEquals(0L, at99.caller().windowRemaining());
        assertEquals(1L, at99.caller().windowRetryAfterMillis(),
                "只读调用方维度也必须按当时真实状态给出 1ms");
        assertEquals(1L, at99.retryAfterMillis(), "顶层等待仍只取路由口径");

        // 跨过窗口边界 100ms：路由新窗口放行并占用；调用方同样滚动恢复后真实占用。
        now[0] = 100L;
        RateLimitDecision rolled = service.check("caller-RW", "orders");
        assertTrue(rolled.allowed(), "窗口滚动后必须放行，不能出现已恢复却拒绝");
        assertEquals(0L, rolled.route().windowRemaining());
        assertEquals(0L, rolled.caller().windowRemaining());
        assertEquals(0L, rolled.retryAfterMillis());

        // 滚动后再次被路由挡住：调用方只读余量已是新窗口耗尽态（0），不会串成上一窗口数字。
        RateLimitDecision afterRolloverReject = service.check("caller-RW", "orders");
        assertFalse(afterRolloverReject.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA,
                afterRolloverReject.reason());
        assertEquals(0L, afterRolloverReject.route().windowRemaining());
        assertEquals(0L, afterRolloverReject.caller().windowRemaining(),
                "只读投影必须落在新窗口，不能口径串台");
        assertEquals(100L, afterRolloverReject.caller().windowRetryAfterMillis());

        TestLogger.log("窗口边界只读投影",
                "route/caller 窗口=1/100ms，时刻 0/99/100/100",
                "只读余量与等待随窗口滚动真实切换，不串窗口",
                "99ms 双维等待1ms；100ms 放行；再拒时只读 caller 已在新窗口耗尽");
    }

    @Test
    void readOnlyBurstProjectionTracksRefillWithoutConsumingTokens() {
        long[] now = {0L};
        // 路由窗口宽、突发=1（第2个请求起被路由突发挡住）；调用方突发=2、回补 1/s。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 1, 1.0d),
                rule(100, 60_000L, 2, 1.0d));
        service.setClockForTests(() -> now[0]);

        // 首请求：路由抽 1（空桶），调用方抽 1（剩 1）
        assertTrue(service.check("caller-RB", "orders").allowed());

        // now=0：路由突发挡住；调用方只读突发余量 = 1（未回补）
        RateLimitDecision d0 = service.check("caller-RB", "orders");
        assertEquals(RateLimitRejectReason.ROUTE_BURST, d0.reason());
        assertEquals(1.0d, d0.caller().burstRemaining(), 1e-9);
        assertEquals(0L, d0.caller().burstRetryAfterMillis(),
                "只读维度仍有 >=1 枚令牌时等待为 0");

        // 推进 500ms：路由回补 0.5 枚仍 <1 继续被拒；只读观察不得抽走调用方令牌。
        now[0] = 500L;
        RateLimitDecision d500 = service.check("caller-RB", "orders");
        assertFalse(d500.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_BURST, d500.reason());
        assertEquals(0.5d, d500.route().burstRemaining(), 1e-9,
                "被拒路由突发余量为真实回补值（不足 1 枚），非正余量");
        // 只读调用方：原有 1 枚 + 0.5 枚回补 = 1.5，且两次只读都没有抽走令牌
        assertEquals(1.5d, d500.caller().burstRemaining(), 1e-9);

        // 再推进到 1000ms：路由回补满 1 枚放行，此时调用方必须恰好剩 1 枚（2 - 两次真实占用），
        // 证明此前的只读投影从未抽过调用方令牌。
        now[0] = 1_000L;
        RateLimitDecision passed = service.check("caller-RB", "orders");
        assertTrue(passed.allowed(), "令牌回补到 1 枚后必须放行，不能出现有余量却被拒");
        assertEquals(1.0d, passed.caller().burstRemaining(), 1e-9,
                "只读展示不得消耗调用方令牌：真实占用始终只有 2 次");

        TestLogger.log("回补期只读投影",
                "route突发1，caller突发2 回补1/s，时刻 0/500/1000",
                "只读余量随回补增长但绝不抽令牌",
                "caller只读 1->1.5；放行时真实剩1，证明零占用");
    }

    // ---------- 并发一致性 ----------

    @Test
    void concurrentDecisionsAlwaysCarryBothDimensionsWithoutContradiction() throws Exception {
        // 突发桶容量均大于各阶段总尝试数（固定时钟下不回补），窗口是唯一拒绝口径，结果精确可推。
        AtomicReference<Boolean> contradiction = new AtomicReference<>(false);

        // ---------- 阶段 1：调用方拒绝路径（路由真实占用，调用方窗口先耗尽）----------
        // 独立服务 + 独立路由（窗口 3000 > 总尝试 2400）；12 个紧调用方各窗口=10，全链路放行恒 120。
        int tightCallers = 12;
        java.util.Map<String, RateLimitProperties.Rule> callerRules = new java.util.HashMap<>();
        for (int c = 0; c < tightCallers; c++) {
            callerRules.put("caller-T" + c, rule(10, 60_000L, 10_000, 1.0d));
        }
        RateLimitService service1 = serviceWithRoute("phase1",
                rule(3_000, 60_000L, 10_000, 1.0d), callerRules);
        service1.setClockForTests(() -> 1_000L);

        // 每 caller 10 线程 × 20 次 = 200 次争抢自己的 10 配额；真实通过 12，拒绝 190。
        PhaseResult p1 = runConcurrent(service1, "phase1", contradiction,
                tightCallers, 10, 20, "caller-T");

        com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitSnapshot after1 =
                service1.snapshot();
        long routeUsed1 = after1.routes().get("phase1").window().used();

        assertFalse(contradiction.get(),
                "不得出现缺字段、负余量、被拒口径正余量、无等待时间等矛盾");
        assertEquals(120L, p1.allowed.get(), "阶段1：每 caller 恰好 10 次全链路放行，共 120");
        // 调用方拒绝发生在路由通过之后，因此这 2280 次仍真实占用路由窗口：120+2280=2400 < 路由配额，
        // 但会把路由窗口记到 2400 尝试上限（窗口硬配额 3000 保证路由自身不拒绝）。
        assertEquals(2_400L, routeUsed1,
                "阶段1：路由对全部 2400 次尝试真实计数（含调用方拒绝的 2280 次）");
        assertEquals(0, p1.routeRejects.get(), "阶段1：路由配额 3000 > 2400 尝试，不应有路由拒绝");
        assertEquals(tightCallers * 10L * (20 - 1), p1.callerRejects.get(),
                "阶段1：每个紧调用方 190 次窗口拒绝，共 2280");
        for (int c = 0; c < tightCallers; c++) {
            var dim = after1.callers().get("caller-T" + c);
            assertEquals(10L, dim.allowedTotal(), "只读投影从未多扣/少计：caller-T" + c + " 恰好 10");
            assertEquals(190L, dim.rejectedTotal());
            assertEquals(10L, dim.window().used());
            assertEquals(0L, dim.window().remaining());
        }

        // ---------- 阶段 2：路由拒绝路径（调用方只读展示，不占额度、不记拒绝）----------
        // 独立服务 + 独立路由（窗口 300）；1 个宽松调用方（窗口 100000）。
        // 32 线程 × 30 次 = 960 次；路由只放 300，其余 660 次全部被路由拒绝。
        // 单调用方组：调用方名必须与 runConcurrent 拼出的前缀+组号一致，即 caller-Z0。
        RateLimitService service2 = serviceWithRoute("phase2",
                rule(300, 60_000L, 10_000, 1.0d),
                java.util.Map.of("caller-Z0", rule(100_000, 60_000L, 10_000, 1.0d)));
        service2.setClockForTests(() -> 1_000L);

        PhaseResult p2 = runConcurrent(service2, "phase2", contradiction,
                1, 32, 30, "caller-Z");

        com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitSnapshot after2 =
                service2.snapshot();
        long routeUsed2 = after2.routes().get("phase2").window().used();
        var z = after2.callers().get("caller-Z0");

        TestLogger.log("并发两维同体",
                "阶段1: 路由300 + 12紧caller(窗口10) 并发；阶段2: 路由300 + 宽松caller 并发",
                "调用方拒绝(路由已占用)与路由拒绝(调用方只读展示)两路径并发下均恒等、不串台",
                "phase1 allowed=" + p1.allowed.get() + " callerReject=" + p1.callerRejects.get()
                        + " routeUsed=" + routeUsed1
                        + " | phase2 allowed=" + p2.allowed.get() + " routeReject=" + p2.routeRejects.get()
                        + " callerReject=" + p2.callerRejects.get() + " routeUsed=" + routeUsed2
                        + " Z.allowed=" + z.allowedTotal() + " Z.rejected=" + z.rejectedTotal());

        assertFalse(contradiction.get(),
                "不得出现缺字段、负余量、被拒口径正余量、无等待时间等矛盾");
        assertEquals(300L, p2.allowed.get(), "阶段2：路由恰好放 300 次，绝不超额");
        assertEquals(660L, p2.routeRejects.get(), "阶段2：其余 660 次全部被路由维度拒绝");
        assertEquals(0, p2.callerRejects.get(),
                "宽松调用方不参与拒绝；被路由挡下时它只读展示，不产生自己的拒绝计数");
        assertEquals(300L, routeUsed2, "路由窗口最终恰好打满 300");
        assertEquals(0L, after2.routes().get("phase2").window().remaining());
        assertEquals(300L, z.allowedTotal(), "caller-Z 真实放行 300，只读展示不多扣");
        assertEquals(0L, z.rejectedTotal(),
                "只读展示路径绝不把调用方计为拒绝（不能误归成调用方错误）");
        assertEquals(300L, z.window().used());
    }

    /** 一次并发压测的计数器集合。 */
    private static final class PhaseResult {
        final AtomicInteger allowed = new AtomicInteger();
        final AtomicInteger routeRejects = new AtomicInteger();
        final AtomicInteger callerRejects = new AtomicInteger();
    }

    /**
     * 让 {@code callerGroups} 个调用方各由 {@code threadsPerCaller} 个线程在同一屏障后
     * 并发发起 {@code requestsPerThread} 次判定，校验每条结论两维度两口径齐全且与占用自洽。
     */
    private PhaseResult runConcurrent(RateLimitService service, String routeKey,
                                      AtomicReference<Boolean> contradiction,
                                      int callerGroups, int threadsPerCaller,
                                      int requestsPerThread, String callerPrefix)
            throws InterruptedException {
        int totalThreads = callerGroups * threadsPerCaller;
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);
        CountDownLatch done = new CountDownLatch(totalThreads);
        PhaseResult r = new PhaseResult();

        for (int t = 0; t < totalThreads; t++) {
            String caller = callerPrefix + (t / threadsPerCaller);
            pool.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        RateLimitDecision d = service.check(caller, routeKey);
                        if (!isInternallyConsistent(d)) {
                            contradiction.set(true);
                        }
                        if (d.allowed()) {
                            r.allowed.incrementAndGet();
                        } else if (d.reason().name().startsWith("ROUTE")) {
                            r.routeRejects.incrementAndGet();
                        } else {
                            r.callerRejects.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                    contradiction.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        return r;
    }

    /**
     * 结论自洽校验（并发下每条决策都必须成立）：
     * 两维度两口径字段齐全且非负；被拒口径不出现“还能立即放行”的正余量；
     * 顶层等待 &gt;0；只读展示维度即使也耗尽，其字段只作信息展示。
     */
    private static boolean isInternallyConsistent(RateLimitDecision d) {
        if (d.route() == null || d.caller() == null
                || d.route().windowRemaining() == null || d.route().burstRemaining() == null
                || d.caller().windowRemaining() == null || d.caller().burstRemaining() == null
                || d.route().windowRetryAfterMillis() == null
                || d.caller().windowRetryAfterMillis() == null
                || d.route().burstRetryAfterMillis() == null
                || d.caller().burstRetryAfterMillis() == null) {
            return false;
        }
        if (d.route().windowRemaining() < 0 || d.caller().windowRemaining() < 0
                || d.route().burstRemaining() < 0 || d.caller().burstRemaining() < 0) {
            return false;
        }
        if (d.allowed()) {
            return d.retryAfterMillis() == 0L;
        }
        if (d.retryAfterMillis() <= 0L) {
            return false;
        }
        return switch (d.reason()) {
            case ROUTE_WINDOW_QUOTA -> d.route().windowRemaining() == 0L;
            case ROUTE_BURST -> d.route().burstRemaining() < 1.0d;
            case CALLER_WINDOW_QUOTA -> d.caller().windowRemaining() == 0L;
            case CALLER_BURST -> d.caller().burstRemaining() < 1.0d;
        };
    }

    @Test
    void readOnlyProjectionForNeverSeenCallerDoesNotCreateStateOrConsumeQuota() {
        // 路由窗口=1（第 2 个请求即被路由挡住）；全新调用方从未出现过。
        RateLimitService service = serviceWithCallers(
                rule(1, 60_000L, 100, 1.0d),
                java.util.Map.of("caller-GHOST", rule(5, 60_000L, 8, 1.0d)));
        service.setClockForTests(() -> 1_000L);

        // 先用别的调用方把路由打满（caller-GHOST 全程不出现）
        assertTrue(service.check("caller-WARMER", "orders").allowed(),
                "未配置规则的调用方按禁用默认规则 -> 不受调用方限流，仅消耗路由");

        RateLimitDecision rejected = service.check("caller-GHOST", "orders");
        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, rejected.reason());
        // 从未出现过的调用方：按其规则展示满额初始真实状态（窗口 5、突发 8）
        assertNotNull(rejected.caller());
        assertEquals(5L, rejected.caller().windowRemaining());
        assertEquals(8.0d, rejected.caller().burstRemaining(), 1e-9);
        // 但只读展示不得把该调用方写进快照状态（不产生“出现过”的副作用、不占任何额度）
        com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitSnapshot snap =
                service.snapshot();
        assertFalse(snap.callers().containsKey("caller-GHOST"),
                "只读投影不得为从未出现的调用方创建缓存条目");

        // 窗口滚动后，该调用方第一次真实到来时必须拿到完整新调用方额度（证明此前没被扣过）
        service.setClockForTests(() -> 61_000L);
        RateLimitDecision firstReal = service.check("caller-GHOST", "orders");
        assertTrue(firstReal.allowed());
        assertEquals(4L, firstReal.caller().windowRemaining(),
                "只读展示期间未占用任何调用方窗口额度");
        assertEquals(7.0d, firstReal.caller().burstRemaining(), 1e-9,
                "只读展示期间未占用任何调用方突发令牌");
    }

    // ---------- helpers ----------

    private RateLimitService serviceWithCallers(RateLimitProperties.Rule routeRule,
                                                java.util.Map<String, RateLimitProperties.Rule> callers) {
        return serviceWithRoute("orders", routeRule, callers);
    }

    private RateLimitService serviceWithRoute(String routeKey,
                                              RateLimitProperties.Rule routeRule,
                                              java.util.Map<String, RateLimitProperties.Rule> callers) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule disabled = new RateLimitProperties.Rule();
        disabled.setWindowQuota(-1);
        disabled.setBurstCapacity(-1);
        props.setDefaults(disabled);
        props.setRoutes(java.util.Map.of(routeKey, routeRule));
        props.setCallers(callers);
        return new RateLimitService(props);
    }

    private RateLimitService serviceWith(RateLimitProperties.Rule routeRule,
                                         RateLimitProperties.Rule callerRule) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule disabled = new RateLimitProperties.Rule();
        disabled.setWindowQuota(-1);
        disabled.setBurstCapacity(-1);
        props.setDefaults(disabled);
        props.setRoutes(java.util.Map.of("orders", routeRule));
        props.setCallers(java.util.Map.of(
                "caller-RW", callerRule, "caller-RB", callerRule,
                "caller-CW", callerRule, "caller-CB", callerRule));
        return new RateLimitService(props);
    }

    private RateLimitProperties.Rule rule(long windowQuota, long windowMillis,
                                          long burst, double refill) {
        RateLimitProperties.Rule r = new RateLimitProperties.Rule();
        r.setWindowQuota(windowQuota);
        r.setWindowMillis(windowMillis);
        r.setBurstCapacity(burst);
        r.setRefillPerSecond(refill);
        return r;
    }
}
