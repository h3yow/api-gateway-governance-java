package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRejectReason;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitSnapshot;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.ScopeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流可执行反馈测试：
 * <ol>
 *   <li>拒绝时 Retry-After 基于窗口/桶真实恢复时刻，且随口径不同；</li>
 *   <li>放行/拒绝结论与两维度剩余额度同源自洽，被短路维度只读不计数；</li>
 *   <li>运行期快照只读（不改计数、不创建维度），且累计数与用量不超硬上限。</li>
 * </ol>
 */
class RateLimitFeedbackTest {

    private RateLimitProperties properties(Long windowQuota, Long windowMillis,
                                          Long burst, Double refill) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule d = new RateLimitProperties.Rule();
        d.setWindowQuota(windowQuota == null ? -1 : windowQuota);
        d.setWindowMillis(windowMillis == null ? -1 : windowMillis);
        d.setBurstCapacity(burst == null ? -1 : burst);
        d.setRefillPerSecond(refill == null ? -1 : refill);
        props.setDefaults(d);
        return props;
    }

    @Test
    void windowRejectCarriesRealRetryAfterAndSelfConsistentQuota() {
        RateLimitService service = new RateLimitService(
                properties(2L, 1_000L, null, null));
        AtomicLong clock = new AtomicLong(0L);
        service.setClockForTests(clock::get);

        assertTrue(service.check("c1", "r1").allowed());
        assertTrue(service.check("c1", "r1").allowed());
        RateLimitDecision rejected = service.check("c1", "r1");
        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, rejected.reason());
        // 窗口 [0,1000)，t=0 耗尽 => 等到 1000ms 滚动。
        assertEquals(1_000L, rejected.retryAfterMillis());
        assertEquals(1L, rejected.retryAfterSeconds());

        // 额度自洽：路由窗口已用尽 remaining=0；调用方维度被路由短路（同一规则也为窗口）。
        assertNotNull(rejected.quota());
        assertNotNull(rejected.quota().route().window());
        assertEquals(2L, rejected.quota().route().window().used());
        assertEquals(0L, rejected.quota().route().window().remaining());
        assertNull(rejected.quota().route().burst(), "未启用突发口径应为 null");

        // 窗口滚动后同结论转为放行，且等待归零。
        clock.set(1_000L);
        RateLimitDecision again = service.check("c1", "r1");
        assertTrue(again.allowed());
        assertEquals(0L, again.retryAfterMillis());
        assertEquals(1L, again.quota().route().window().used());
        assertEquals(1L, again.quota().route().window().remaining());

        TestLogger.log("窗口拒绝反馈",
                "窗口=2/1000ms，连打3次后滚动",
                "retryAfter=到窗口边界；额度=同源原子快照",
                "拒绝等待1000ms,remaining=0；滚动后放行 remaining=1");
    }

    @Test
    void burstRejectCarriesRateBasedRetryAfter() {
        RateLimitService service = new RateLimitService(
                properties(null, null, 1L, 2.0d));
        AtomicLong clock = new AtomicLong(0L);
        service.setClockForTests(clock::get);

        assertTrue(service.check("c2", "r2").allowed());
        RateLimitDecision rejected = service.check("c2", "r2");
        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_BURST, rejected.reason());
        // 2 枚/秒 => 空桶补 1 枚需 500ms。
        assertEquals(500L, rejected.retryAfterMillis());
        assertEquals(1L, rejected.retryAfterSeconds());
        assertEquals(0.0d, rejected.quota().route().burst().availableTokens(), 1e-9);

        clock.set(500L);
        assertTrue(service.check("c2", "r2").allowed(), "回补出1枚后应放行");
    }

    @Test
    void allowedDecisionCarriesPostAcquisitionQuotaForBothDimensions() {
        // 路由：窗口 10；调用方：窗口 2。默认规则各自配置。
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule d = new RateLimitProperties.Rule();
        d.setWindowQuota(-1);
        d.setBurstCapacity(-1);
        props.setDefaults(d);
        RateLimitProperties.Rule route = new RateLimitProperties.Rule();
        route.setWindowQuota(10);
        route.setWindowMillis(60_000L);
        props.setRoutes(Map.of("orders", route));
        RateLimitProperties.Rule caller = new RateLimitProperties.Rule();
        caller.setWindowQuota(2);
        caller.setWindowMillis(60_000L);
        props.setCallers(Map.of("vip", caller));

        RateLimitService service = new RateLimitService(props);
        RateLimitDecision ok = service.check("vip", "orders");
        assertTrue(ok.allowed());
        assertEquals(9L, ok.quota().route().window().remaining());
        assertEquals(1L, ok.quota().caller().window().remaining());
        // 突发口径在两个维度都未启用。
        assertNull(ok.quota().route().burst());
        assertNull(ok.quota().caller().burst());
    }

    @Test
    void shortCircuitedCallerDimensionIsNotChargedButVisibleReadOnly() {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule d = new RateLimitProperties.Rule();
        d.setWindowQuota(-1);
        d.setBurstCapacity(-1);
        props.setDefaults(d);
        RateLimitProperties.Rule route = new RateLimitProperties.Rule();
        route.setWindowQuota(1);
        route.setWindowMillis(60_000L);
        props.setRoutes(Map.of("orders", route));
        RateLimitProperties.Rule caller = new RateLimitProperties.Rule();
        caller.setWindowQuota(10);
        caller.setWindowMillis(60_000L);
        props.setCallers(Map.of("c", caller));

        RateLimitService service = new RateLimitService(props);
        assertTrue(service.check("c", "orders").allowed());
        // 第1次通过后调用方窗口已占用 1（10->9）。
        long callerRemainingBeforeReject =
                service.snapshot().callers().get("c").window().remaining();
        assertEquals(9L, callerRemainingBeforeReject);

        RateLimitDecision rejected = service.check("c", "orders");
        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_WINDOW_QUOTA, rejected.reason());
        // 调用方维度被路由短路：拒绝前后其窗口计数不得变化（只读 peek，不占用）。
        assertEquals(callerRemainingBeforeReject,
                rejected.quota().caller().window().remaining(),
                "被短路维度的剩余额度不应因这次拒绝而变化");
        ScopeSnapshot callerSnap = service.snapshot().callers().get("c");
        assertEquals(1L, callerSnap.totalAllowed(), "短路拒绝不应给调用方维度再记通过");
        assertEquals(0L, callerSnap.totalRejected(), "短路拒绝不计入调用方维度拒绝");
        ScopeSnapshot routeSnap = service.snapshot().routes().get("orders");
        assertEquals(1L, routeSnap.totalAllowed());
        assertEquals(1L, routeSnap.totalRejected());
    }

    @Test
    void snapshotIsReadOnlyAndBoundedAndConsistent() {
        RateLimitService service = new RateLimitService(
                properties(2L, 60_000L, 3L, 10.0d));

        service.check("c", "r");
        service.check("c", "r");
        service.check("c", "r"); // 第3次：路由窗口(2)耗尽

        // 查询一个从未出现过的维度，不应因为 snapshot 被创建：先拍空快照。
        RateLimitSnapshot before = service.snapshot();
        assertFalse(before.routes().containsKey("never-seen-route"));
        RateLimitSnapshot after = service.snapshot();
        assertFalse(after.routes().containsKey("never-seen-route"), "快照查询不得创建新维度");

        ScopeSnapshot route = after.routes().get("r");
        assertNotNull(route);
        // 边界：窗口用量绝不超硬上限，桶令牌不超容量、不为负。
        assertTrue(route.window().used() <= route.window().limit());
        assertTrue(route.window().remaining() >= 0L);
        assertTrue(route.burst().availableTokens() <= route.burst().capacity());
        assertTrue(route.burst().availableTokens() >= 0d);
        // 累计：2 放行（前两次全过），1 拒绝（第三次在路由窗口被短路）。
        assertEquals(2L, route.totalAllowed());
        assertEquals(1L, route.totalRejected());

        // 连续两次只读快照不改变任何累计计数。
        long allowed1 = service.snapshot().routes().get("r").totalAllowed();
        long allowed2 = service.snapshot().routes().get("r").totalAllowed();
        assertEquals(allowed1, allowed2);

        TestLogger.log("配额快照只读",
                "窗口=2,桶=3，3次请求后连续查询快照",
                "快照不计数/不建维度；用量有界、计数确定",
                "route allowed=2 rejected=1, window.used=2<=2, burst<=3");
    }

    @Test
    void cumulativeCountersClassifyAllowedAndRejectedPerDimension() {
        RateLimitService service = new RateLimitService(
                properties(1L, 60_000L, null, null));
        service.check("c", "r"); // pass both
        service.check("c", "r"); // route reject (caller short-circuited)
        ScopeSnapshot route = service.snapshot().routes().get("r");
        ScopeSnapshot caller = service.snapshot().callers().get("c");
        assertEquals(1L, route.totalAllowed());
        assertEquals(1L, route.totalRejected());
        // 调用方维度：第1次通过计 1；第2次在路由处短路，既不记放行也不记拒绝。
        assertEquals(1L, caller.totalAllowed());
        assertEquals(0L, caller.totalRejected());
    }
}
