package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.FixedWindowQuotaLimiter;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRejectReason;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.TokenBucketLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剩余额度观测测试：
 * 两种口径（固定窗口配额 / 突发容量）下，结论中的剩余额度必须与“是否放行 + 实际占用”严格对得上，
 * 且恢复等待时间必须按当时真实状态推算。
 */
class RateLimitQuotaObservationTest {

    // ---------- 固定窗口 ----------

    @Test
    void windowAcquireReturnsMatchingRemainingQuota() {
        FixedWindowQuotaLimiter limiter = new FixedWindowQuotaLimiter(3, 100L);
        var r1 = limiter.acquire(0L);
        var r2 = limiter.acquire(10L);
        var r3 = limiter.acquire(20L);
        var r4 = limiter.acquire(30L);

        assertTrue(r1.allowed() && r2.allowed() && r3.allowed());
        assertEquals(2L, r1.remaining());
        assertEquals(1L, r2.remaining());
        assertEquals(0L, r3.remaining(), "最后一个配额被占用后剩余必须为 0，而不是负数");
        assertFalse(r4.allowed());
        assertEquals(0L, r4.remaining(), "已用尽时拒绝结论剩余必须为 0");
        assertEquals(70L, r4.retryAfterMillis(), "拒绝时等待必须是距窗口结束的真实时间 (100-30)");
        // 一致性：实际计数 = 已放行 3；剩余 0 + 已用 3 = 配额 3
        assertEquals(3L, limiter.currentCount(30L));
        TestLogger.log("窗口剩余额度一致",
                "quota=3 window=100, 0/10/20/30ms 各请求一次",
                "占用结论与计数同源：remaining=quota-占用后已用",
                "放行3剩余2/1/0；第4次拒绝 remaining=0 wait=70ms");
    }

    @Test
    void windowRolloverRestoresFullQuotaAndZeroWait() {
        FixedWindowQuotaLimiter limiter = new FixedWindowQuotaLimiter(2, 100L);
        limiter.acquire(0L);
        var rejected = limiter.acquire(99L); // 第二个配额
        var second = limiter.acquire(99L);  // 用尽
        assertFalse(second.allowed());
        assertEquals(1L, second.retryAfterMillis(), "99ms 时距窗口结束恰为 1ms");
        // 滚动到新窗口：恢复为整配额
        var rolled = limiter.acquire(100L);
        assertTrue(rolled.allowed());
        assertEquals(1L, rolled.remaining());
        assertEquals(0L, rolled.retryAfterMillis());
        TestLogger.log("窗口滚动恢复",
                "quota=2 window=100, 用尽后 99->100ms",
                "新窗口首个请求原子翻转为(新起点,1)",
                "拒绝 wait=1ms；滚动后放行 remaining=1 wait=0");
    }

    // ---------- 令牌桶 ----------

    @Test
    void burstAcquireReturnsMatchingRemainingTokens() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, 10.0d);
        var b1 = limiter.acquire(0L);
        var b2 = limiter.acquire(0L);
        var b3 = limiter.acquire(0L);
        var b4 = limiter.acquire(0L);

        assertTrue(b1.allowed() && b2.allowed() && b3.allowed());
        assertEquals(2.0d, b1.remaining(), 1e-9);
        assertEquals(1.0d, b2.remaining(), 1e-9);
        assertEquals(0.0d, b3.remaining(), 1e-9);
        assertFalse(b4.allowed(), "桶空必须拒绝，不能出现 0 余量还放行");
        assertEquals(0.0d, b4.remaining(), 1e-9, "拒绝结论剩余不得为负");
        assertTrue(b4.retryAfterMillis() >= 100L,
                "10/s 回补 1 枚令牌至少需要 100ms，实际=" + b4.retryAfterMillis());
        TestLogger.log("突发剩余额度一致",
                "capacity=3 refill=10/s，同一时刻抽 4 次",
                "remaining=取走后令牌数；拒绝时 wait=ceil((1-remaining)/rate*1000)",
                "放行3剩余2/1/0；第4次拒绝 wait>=100ms");
    }

    @Test
    void burstRetryWaitIsComputedFromRealRefillState() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(2, 10.0d);
        limiter.acquire(0L);
        limiter.acquire(0L); // 抽干
        var empty = limiter.acquire(0L);
        assertFalse(empty.allowed());
        // 桶空：回补 1 枚需 100ms
        assertEquals(100L, empty.retryAfterMillis());
        // 50ms 后桶内 0.5 枚：仍拒绝，等待应缩短为约 50ms
        var half = limiter.acquire(50L);
        assertFalse(half.allowed(), "0.5 枚令牌不足以放行");
        assertEquals(0.5d, half.remaining(), 1e-9);
        assertEquals(50L, half.retryAfterMillis(), "等待必须随真实回补进度缩短，不能是固定常量");
        // 再到 100ms：累计 1 枚，放行
        var refilled = limiter.acquire(100L);
        assertTrue(refilled.allowed());
        TestLogger.log("突发等待随状态变化",
                "capacity=2 refill=10/s 抽干后在 0/50/100ms 探测",
                "wait=ceil((1-桶内)/rate*1000)",
                "wait 100ms -> 50ms -> 100ms 放行");
    }

    // ---------- RateLimitService：两维度结论同体 ----------

    @Test
    void decisionCarriesBothDimensionsAndBothCalibers() {
        RateLimitService service = serviceWith(
                rule(5, 60_000L, 4, 1.0d),
                rule(3, 60_000L, 2, 1.0d));
        service.setClockForTests(() -> 1_000L);

        RateLimitDecision d = service.check("caller-Q", "orders");
        assertTrue(d.allowed());
        // 路由：窗口配额 5 -> 剩 4；突发容量 4 -> 剩 3
        assertNotNull(d.route());
        assertEquals(4L, d.route().windowRemaining());
        assertEquals(3.0d, d.route().burstRemaining(), 1e-9);
        // 调用方：窗口配额 3 -> 剩 2；突发容量 2 -> 剩 1
        assertNotNull(d.caller());
        assertEquals(2L, d.caller().windowRemaining());
        assertEquals(1.0d, d.caller().burstRemaining(), 1e-9);
        TestLogger.log("两维度剩余额度",
                "route(窗口5/突发4) + caller(窗口3/突发2)，首请求",
                "每个口径的剩余=上限-本次实际占用",
                "route 窗口4/突发3；caller 窗口2/突发1");
    }

    @Test
    void rejectedByCallerWindowStillReportsRouteRemaining() {
        RateLimitService service = serviceWith(
                rule(100, 60_000L, -1, -1),
                rule(1, 60_000L, -1, -1));
        service.setClockForTests(() -> 1_000L);
        assertTrue(service.check("caller-W", "orders").allowed());
        RateLimitDecision rejected = service.check("caller-W", "orders");
        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.CALLER_WINDOW_QUOTA, rejected.reason());
        // 路由维度本次同样真实占用：窗口剩 98（前两次请求各占 1）
        assertEquals(98L, rejected.route().windowRemaining());
        assertEquals(0L, rejected.caller().windowRemaining());
        assertTrue(rejected.retryAfterMillis() > 0L, "必须给出真实恢复等待");
        TestLogger.log("调用方窗口拒绝一致性",
                "route 窗口=100，caller 窗口=1，连续2次",
                "拒绝发生在调用方维度时，路由维度已占用且剩余额度同步带回",
                "route.remaining=98 caller.remaining=0 reason=CALLER_WINDOW");
    }

    @Test
    void rejectedByBurstReportsZeroBurstButWindowQuotaCanRemain() {
        // 窗口很宽、突发容量=1：第 2 个请求被突发口径拒绝，但窗口口径仍有大量余额。
        RateLimitService service = serviceWith(
                rule(100, 60_000L, 1, 0.001d),
                rule(100, 60_000L, 1, 0.001d));
        service.setClockForTests(() -> 1_000L);
        assertTrue(service.check("caller-B", "orders").allowed());
        RateLimitDecision rejected = service.check("caller-B", "orders");
        assertFalse(rejected.allowed());
        assertEquals(RateLimitRejectReason.ROUTE_BURST, rejected.reason());
        assertEquals(0.0d, rejected.route().burstRemaining(), 1e-9);
        assertEquals(98L, rejected.route().windowRemaining(),
                "突发耗尽不等于窗口配额耗尽，两种口径必须分开报告");
        // 调用方维度尚未判定（在路由维度短路）
        assertNull(rejected.caller());
        TestLogger.log("两口径独立报告",
                "窗口=100 突发=1，连续2次",
                "突发口径拒绝时窗口口径仍有余量，二者不互相冒充",
                "route.burst=0 route.window=98 caller=null(短路)");
    }

    // ---------- helpers ----------

    private RateLimitService serviceWith(RateLimitProperties.Rule routeRule,
                                         RateLimitProperties.Rule callerRule) {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.Rule disabled = new RateLimitProperties.Rule();
        disabled.setWindowQuota(-1);
        disabled.setBurstCapacity(-1);
        props.setDefaults(disabled);
        props.setRoutes(java.util.Map.of("orders", routeRule));
        props.setCallers(java.util.Map.of("caller-Q", callerRule,
                "caller-W", callerRule, "caller-B", callerRule));
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
