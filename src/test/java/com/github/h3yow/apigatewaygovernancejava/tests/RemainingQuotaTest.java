package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.FixedWindowQuotaLimiter;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.TokenBucketLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剩余额度语义测试：固定窗口配额（硬上限口径）与令牌桶突发（稳态+突口径）
 * 的剩余/已用在放行、耗尽、窗口滚动、令牌回补各边界时点都必须与判定自洽。
 */
class RemainingQuotaTest {

    // ---------- 固定窗口配额 ----------

    @Test
    void windowRemainingTracksUsedAndRollsOver() {
        FixedWindowQuotaLimiter w = new FixedWindowQuotaLimiter(3, 100L);

        var r1 = w.acquire(0L);
        assertTrue(r1.allowed());
        assertEquals(1L, r1.used());
        assertEquals(2L, r1.remaining(), "放行后剩余=上限-本次占用");
        assertEquals(0L, r1.retryAfterMillis());
        assertEquals(0L, r1.windowStartMillis());
        assertEquals(100L, r1.resetAtMillis());

        var r2 = w.acquire(40L);
        assertTrue(r2.allowed());
        assertEquals(2L, r2.used());
        assertEquals(1L, r2.remaining());

        var r3 = w.acquire(80L);
        assertTrue(r3.allowed());
        assertEquals(3L, r3.used());
        assertEquals(0L, r3.remaining(), "用满时剩余为 0，绝不为负");

        var r4 = w.acquire(99L);
        assertFalse(r4.allowed());
        assertEquals(3L, r4.used());
        assertEquals(0L, r4.remaining(), "已用尽却拒绝：剩余必须是 0，不能还有额度");
        assertEquals(1L, r4.retryAfterMillis(), "99ms 被拒，距窗口滚动(100ms)等待 1ms");

        // 滚动到 [100,200)：归零重新累计，剩余恢复为上限。
        var r5 = w.acquire(100L);
        assertTrue(r5.allowed());
        assertEquals(1L, r5.used());
        assertEquals(2L, r5.remaining());
        assertEquals(100L, r5.windowStartMillis());
        assertEquals(200L, r5.resetAtMillis());

        TestLogger.log("窗口剩余额度",
                "quota=3 window=100ms，时刻 0/40/80/99/100",
                "结论与(used,remaining)来自同一原子快照，滚动即归零",
                "剩余序列 2/1/0，99ms 拒绝等待 1ms，滚动后恢复剩余2");
    }

    @Test
    void windowRetryAfterIsBasedOnResetBoundary() {
        FixedWindowQuotaLimiter w = new FixedWindowQuotaLimiter(1, 1000L);
        assertTrue(w.acquire(250L).allowed());
        var rejected = w.acquire(750L);
        assertFalse(rejected.allowed());
        // 当前窗口 [0,1000)，750 时刻被拒 => 等 250ms 到 1000。
        assertEquals(250L, rejected.retryAfterMillis());
        assertEquals(rejected.resetAtMillis() - 750L, rejected.retryAfterMillis());
    }

    @Test
    void windowPeekIsReadOnly() {
        FixedWindowQuotaLimiter w = new FixedWindowQuotaLimiter(5, 100L);
        w.acquire(10L);
        FixedWindowQuotaLimiter.WindowView v1 = w.peek(20L);
        assertEquals(5L, v1.limit());
        assertEquals(1L, v1.used());
        assertEquals(4L, v1.remaining());
        // 连续只读两次，结果一致且不改变计数。
        FixedWindowQuotaLimiter.WindowView v2 = w.peek(20L);
        assertEquals(v1.used(), v2.used());
        assertEquals(1L, w.currentCount(20L), "peek 不得改变用量");
    }

    // ---------- 令牌桶突发 ----------

    @Test
    void burstRemainingDrainedAndRefilledConsistently() {
        TokenBucketLimiter b = new TokenBucketLimiter(2, 10.0d);

        var r1 = b.acquire(0L);
        assertTrue(r1.allowed());
        assertEquals(1.0d, r1.remaining(), 1e-9, "满桶2取1后剩1");

        var r2 = b.acquire(0L);
        assertTrue(r2.allowed());
        assertEquals(0.0d, r2.remaining(), 1e-9, "取第2枚后桶空，剩余0");

        var r3 = b.acquire(0L);
        assertFalse(r3.allowed());
        assertEquals(0.0d, r3.remaining(), 1e-9, "桶空被拒：剩余必须是0");
        // 10/s => 补1枚需要 100ms。
        assertEquals(100L, r3.retryAfterMillis());

        // 50ms 后只补 0.5 枚，仍不足 1：拒绝，等待约 50ms。
        var r4 = b.acquire(50L);
        assertFalse(r4.allowed());
        assertEquals(0.5d, r4.remaining(), 1e-9);
        assertTrue(r4.retryAfterMillis() <= 50L && r4.retryAfterMillis() >= 49L,
                "按填充速率推算的等待应约50ms，实际=" + r4.retryAfterMillis());

        // 到 100ms 补出 1 枚：放行，占用后回到 0。
        var r5 = b.acquire(100L);
        assertTrue(r5.allowed());
        assertEquals(0.0d, r5.remaining(), 1e-9);
    }

    @Test
    void burstRetryAfterIsComputedFromRefillRateNotConstant() {
        // 1 枚/秒 => 空桶后补 1 枚需要 1000ms；2 枚/秒 => 500ms，证明等待随速率变化。
        TokenBucketLimiter slow = new TokenBucketLimiter(1, 1.0d);
        slow.acquire(0L);
        assertEquals(1000L, slow.acquire(0L).retryAfterMillis());

        TokenBucketLimiter fast = new TokenBucketLimiter(1, 2.0d);
        fast.acquire(0L);
        assertEquals(500L, fast.acquire(0L).retryAfterMillis());
    }

    @Test
    void burstNeverExceedsCapacityAfterIdle() {
        TokenBucketLimiter b = new TokenBucketLimiter(3, 10.0d);
        b.acquire(0L);
        b.acquire(0L);
        // 空闲很久后桶最多回到容量，不会累积超额令牌。
        TokenBucketLimiter.BucketView v = b.peek(1_000_000L);
        assertEquals(3L, v.capacity());
        assertEquals(3.0d, v.availableTokens(), 1e-9);
        assertEquals(0.0d, v.usedTokens(), 1e-9);
    }

    @Test
    void burstPeekDoesNotConsume() {
        TokenBucketLimiter b = new TokenBucketLimiter(2, 10.0d);
        TokenBucketLimiter.BucketView v1 = b.peek(0L);
        TokenBucketLimiter.BucketView v2 = b.peek(0L);
        assertEquals(2.0d, v1.availableTokens(), 1e-9);
        assertEquals(2.0d, v2.availableTokens(), 1e-9, "peek 不消耗令牌");
        assertTrue(b.acquire(0L).allowed());
    }
}
