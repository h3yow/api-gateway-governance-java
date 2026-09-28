package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRejectReason;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流结论完整性测试：无论请求先被哪个维度、哪种口径拒绝（或放行），结论都必须
 * 同时携带路由、调用方两个维度在固定窗口与突发容量两种口径下的剩余额度。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>被短路（未实际判定）的维度以同一时刻真实状态只读展示，不占额度、不改计数；</li>
 *   <li>被拒口径绝不出现正余量；放行口径的数值是扣减后的余量；</li>
 *   <li>只读补全不改变拒绝原因、等待时间，也不影响后续请求的判定结果。</li>
 * </ul>
 */
class RateLimitDecisionCompletenessTest {

    // ---------- 四种拒绝场景：两维度两口径都必须齐全 ----------

    @Test
    void routeWindowRejectStillCarriesCallerBothCalibers() {
        // 路由：窗口=1 突发=10；调用方：窗口=5 突发=4。第 2 次请求撞路由窗口。
        RateLimitService service = serviceWith(
                rule(1, 60_000L, 10, 1.0d),
                rule(5, 60_000L, 4, 1.0d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-RW", "orders").allowed());
        RateLimitDecision d = service.check("caller-RW", "orders");

        assertFalse(d.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, d.reason());
        // 路由维度：被拒窗口口径余量必须为 0；突发口径本次未走到，
        // 但首请求已消耗 1 枚，只读视图按真实状态展示为 9（本次拒绝不再消耗）
        assertBothCalibersPresent(d.route());
        assertEquals(0L, d.route().windowRemaining());
        assertEquals(9.0d, d.route().burstRemaining(), 1e-9,
                "路由突发未被本次拒绝消耗，展示首请求消耗后的真实余量 9");
        // 调用方维度：首请求已真实占用（窗口 5->4、突发 4->3），本次拒绝只读不扣
        assertBothCalibersPresent(d.caller());
        assertEquals(4L, d.caller().windowRemaining());
        assertEquals(3.0d, d.caller().burstRemaining(), 1e-9);
        assertTrue(d.retryAfterMillis() > 0L, "等待时间仍按路由窗口真实恢复状态计算");
        TestLogger.log("路由窗口拒绝完整性",
                "route(窗口1/突发10) caller(窗口5/突发4)，连续2次",
                "路由窗口拒绝短路，调用方维度只读投影（首请求的占用真实存在）",
                "route.window=0 route.burst=9 caller.window=4 caller.burst=3");
    }

    @Test
    void routeBurstRejectStillCarriesCallerBothCalibers() {
        // 路由：窗口=100 突发=1（回补极慢）；调用方：窗口=5 突发=4。第 2 次撞路由突发。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 1, 0.001d),
                rule(5, 60_000L, 4, 1.0d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-RB", "orders").allowed());
        RateLimitDecision d = service.check("caller-RB", "orders");

        assertFalse(d.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_BURST, d.reason());
        assertBothCalibersPresent(d.route());
        assertEquals(0.0d, d.route().burstRemaining(), 1e-9);
        assertEquals(98L, d.route().windowRemaining(),
                "本次请求先通过窗口口径（占用 1）后在突发口径被拒，窗口按占用后余量展示");
        // 调用方维度首请求已真实占用，本次短路只读投影（窗口 5->4、突发 4->3）
        assertBothCalibersPresent(d.caller());
        assertEquals(4L, d.caller().windowRemaining());
        assertEquals(3.0d, d.caller().burstRemaining(), 1e-9);
        TestLogger.log("路由突发拒绝完整性",
                "route(窗口100/突发1) caller(窗口5/突发4)，连续2次",
                "路由突发拒绝短路，调用方维度只读投影",
                "route.burst=0 route.window=98 caller.window=4 caller.burst=3");
    }

    @Test
    void callerWindowRejectCarriesBothDimensionsBothCalibers() {
        // 路由：窗口=100 突发=10；调用方：窗口=1 突发=4。第 2 次撞调用方窗口。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 10, 1.0d),
                rule(1, 60_000L, 4, 1.0d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-CW", "orders").allowed());
        RateLimitDecision d = service.check("caller-CW", "orders");

        assertFalse(d.allowed());
        assertEquals(RateLimitRejectReason.CALLER_WINDOW_QUOTA, d.reason());
        // 路由维度本次已真实占用：窗口 100-2=98，突发 10-2=8
        assertBothCalibersPresent(d.route());
        assertEquals(98L, d.route().windowRemaining());
        assertEquals(8.0d, d.route().burstRemaining(), 1e-9);
        // 调用方：窗口被拒余量 0；突发未走到，保持第 1 次消耗后的 3
        assertBothCalibersPresent(d.caller());
        assertEquals(0L, d.caller().windowRemaining());
        assertEquals(3.0d, d.caller().burstRemaining(), 1e-9,
                "调用方窗口拒绝不得额外套扣突发令牌");
        TestLogger.log("调用方窗口拒绝完整性",
                "route(窗口100/突发10) caller(窗口1/突发4)，连续2次",
                "调用方窗口拒绝，路由维度已占用同步带回",
                "route.window=98 route.burst=8 caller.window=0 caller.burst=3");
    }

    @Test
    void callerBurstRejectCarriesBothDimensionsBothCalibers() {
        // 路由：窗口=100 突发=10；调用方：窗口=100 突发=1（回补极慢）。第 2 次撞调用方突发。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 10, 1.0d),
                rule(100, 60_000L, 1, 0.001d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-CB", "orders").allowed());
        RateLimitDecision d = service.check("caller-CB", "orders");

        assertFalse(d.allowed());
        assertEquals(RateLimitRejectReason.CALLER_BURST, d.reason());
        assertBothCalibersPresent(d.route());
        assertEquals(98L, d.route().windowRemaining());
        assertEquals(8.0d, d.route().burstRemaining(), 1e-9);
        assertBothCalibersPresent(d.caller());
        assertEquals(0.0d, d.caller().burstRemaining(), 1e-9);
        assertEquals(98L, d.caller().windowRemaining(),
                "调用方窗口已实际占用 2 次，突发拒绝不影响窗口口径数值");
        TestLogger.log("调用方突发拒绝完整性",
                "route(窗口100/突发10) caller(窗口100/突发1)，连续2次",
                "调用方突发拒绝，两维度窗口口径均为扣减后真实余量",
                "route.window=98 route.burst=8 caller.window=98 caller.burst=0");
    }

    // ---------- 只读展示不扣额度、不改变后续判定 ----------

    @Test
    void routeRejectedReadsDoNotConsumeCallerQuota() {
        // 路由窗口=1：第 1 次放行后，后续都被路由窗口短路；调用方窗口=2 突发=2。
        RateLimitService service = serviceWith(
                rule(1, 60_000L, -1, -1),
                rule(2, 60_000L, 2, 0.001d));
        service.setClockForTests(() -> 1_000L);

        assertTrue(service.check("caller-NC", "orders").allowed());
        // 连续 3 次被路由窗口拒绝：每次都只读展示调用方余量，不得扣减
        for (int i = 0; i < 3; i++) {
            RateLimitDecision d = service.check("caller-NC", "orders");
            assertFalse(d.allowed());
            assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, d.reason());
            assertEquals(1L, d.caller().windowRemaining(),
                    "只读展示不得消耗调用方窗口配额 (第 " + (i + 1) + " 次拒绝)");
            assertEquals(1.0d, d.caller().burstRemaining(), 1e-9,
                    "只读展示不得消耗调用方突发令牌 (第 " + (i + 1) + " 次拒绝)");
        }
        // 换一条不限流的路由：调用方真实余量必须仍是第 1 次请求后的状态，
        // 即拒绝期间的只读投影没有多扣任何额度，后续判定结果不受影响。
        RateLimitDecision d = service.check("caller-NC", "other");
        assertTrue(d.allowed(), "调用方额度未被只读投影消耗，换路由后必须放行");
        assertEquals(0L, d.caller().windowRemaining());
        assertEquals(0.0d, d.caller().burstRemaining(), 1e-9);
        TestLogger.log("只读不扣额度",
                "route 窗口=1，caller 窗口=2/突发=2；3 次路由拒绝后换路由",
                "拒绝期间调用方维度只读投影，计数不变",
                "3 次拒绝均 caller.window=1/burst=1；换路由后放行且余量归零");
    }

    // ---------- 边界时刻 ----------

    @Test
    void windowBoundaryReadOnlyViewRollsWithRealState() {
        // 窗口 100ms：路由窗口=1，调用方窗口=1。用可控时钟踩在边界上。
        RateLimitService service = serviceWith(
                rule(1, 100L, -1, -1),
                rule(1, 100L, -1, -1));
        AtomicLong now = new AtomicLong(0L);
        service.setClockForTests(now::get);

        assertTrue(service.check("caller-BD", "orders").allowed(), "t=0 首请求放行");

        now.set(99L);
        RateLimitDecision atBoundary = service.check("caller-BD", "orders");
        assertFalse(atBoundary.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, atBoundary.reason());
        assertEquals(1L, atBoundary.retryAfterMillis(),
                "t=99 距窗口结束恰为 1ms，等待时间按真实状态计算");
        assertEquals(0L, atBoundary.route().windowRemaining());
        // 调用方窗口在同一窗口内也已用尽（第 1 次请求消耗），只读展示必须反映真实 0 余量
        assertEquals(0L, atBoundary.caller().windowRemaining());
        assertEquals(1L, atBoundary.caller().windowRetryAfterMillis(),
                "只读维度的等待时间同样按真实窗口状态推算");

        now.set(100L);
        RateLimitDecision rolled = service.check("caller-BD", "orders");
        assertTrue(rolled.allowed(), "窗口滚动后两维度配额都恢复，必须放行");
        assertEquals(0L, rolled.route().windowRemaining());
        assertEquals(0L, rolled.caller().windowRemaining());
        assertEquals(0L, rolled.retryAfterMillis());
        TestLogger.log("窗口边界只读一致性",
                "两维度窗口=1/100ms，t=0/99/100",
                "只读投影与真实窗口滚动同源",
                "t=99 双维度余量0、wait=1ms；t=100 滚动后放行");
    }

    // ---------- 并发一致性 ----------

    @Test
    void concurrentRouteRejectsAlwaysCarryConsistentCallerView() throws Exception {
        // 路由窗口=50（会被打爆），调用方窗口/突发足够大但有限。
        int routeQuota = 50;
        long callerWindow = 10_000L;
        long callerBurst = 10_000L;
        RateLimitService service = serviceWith(
                rule(routeQuota, 60_000L, 10_000L, 0.001d),
                rule(callerWindow, 60_000L, callerBurst, 0.001d));
        service.setClockForTests(() -> 1_000L);

        int threads = 32;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.Queue<RateLimitDecision> decisions = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        decisions.add(service.check("caller-CC", "orders"));
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

        long allowed = decisions.stream().filter(RateLimitDecision::allowed).count();
        assertEquals(routeQuota, allowed, "并发下放行数必须恰好等于路由窗口配额");
        for (RateLimitDecision d : decisions) {
            // 每条结论（放行或拒绝）都必须同时带齐两个维度
            assertNotNull(d.route(), "任何结论都不得缺失路由维度");
            assertNotNull(d.caller(), "任何结论都不得缺失调用方维度");
            assertBothCalibersPresent(d.route());
            assertBothCalibersPresent(d.caller());
            // 余量不得为负、不得超过上限
            assertTrue(d.route().windowRemaining() >= 0L
                    && d.route().windowRemaining() <= routeQuota);
            assertTrue(d.caller().windowRemaining() >= 0L
                    && d.caller().windowRemaining() <= callerWindow);
            assertTrue(d.caller().burstRemaining() >= 0.0d
                    && d.caller().burstRemaining() <= callerBurst);
            if (!d.allowed()) {
                // 全部被拒请求都因路由窗口耗尽：被拒口径余量必须为 0，原因不得串台
                assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, d.reason());
                assertEquals(0L, d.route().windowRemaining(),
                        "被拒口径不得出现正余量");
                assertTrue(d.retryAfterMillis() > 0L);
            }
        }
        // 总量对账：调用方窗口实际消耗必须恰好等于放行数（只读投影没有多扣）。
        // 放行结论里调用方窗口余量的最小值 = 上限 - 放行数（最后一个放行请求看到的余量）。
        long minCallerRemaining = decisions.stream()
                .filter(RateLimitDecision::allowed)
                .mapToLong(d -> d.caller().windowRemaining())
                .min().orElseThrow();
        assertEquals(callerWindow - allowed, minCallerRemaining,
                "调用方实际消耗必须恰好等于放行数，只读投影不得多扣");
        TestLogger.log("并发结论完整性",
                "threads=" + threads + " perThread=" + perThread + " route窗口=" + routeQuota,
                "每条结论两维度两口径齐全、非负、不超上限；被拒口径余量恒 0",
                "allowed=" + allowed + " rejected=" + (decisions.size() - allowed)
                        + " caller消耗=" + allowed + "（与放行数一致）");
    }

    // ---------- helpers ----------

    private void assertBothCalibersPresent(RateLimitDecision.DimensionQuota quota) {
        assertNotNull(quota.windowRemaining(), "固定窗口口径必须携带");
        assertNotNull(quota.burstRemaining(), "突发容量口径必须携带");
    }

    private RateLimitService serviceWith(RateLimitProperties.Rule routeRule,
                                         RateLimitProperties.Rule callerRule) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule disabled = new RateLimitProperties.Rule();
        disabled.setWindowQuota(-1);
        disabled.setBurstCapacity(-1);
        props.setDefaults(disabled);
        props.setRoutes(Map.of("orders", routeRule));
        props.setCallers(Map.of("caller-RW", callerRule, "caller-RB", callerRule,
                "caller-CW", callerRule, "caller-CB", callerRule,
                "caller-NC", callerRule, "caller-BD", callerRule,
                "caller-CC", callerRule));
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
