package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 令牌桶突发限流器。
 *
 * <p>语义明确：
 * <ul>
 *   <li>桶容量 {@code capacity}：任意时刻最多累计的令牌数，即允许的瞬时突发请求上限；</li>
 *   <li>填充速率 {@code refillTokensPerSecond}：稳态平均放行速率；</li>
 *   <li>每次请求惰性填充令牌：{@code min(capacity, tokens + elapsedSeconds*rate)}，再尝试取 1 枚；</li>
 *   <li>桶满时新令牌被丢弃（不累积），桶空时请求被拒绝。</li>
 * </ul>
 *
 * <p>{@code (tokens, lastRefillMillis)} 作为不可变快照用 CAS 更新，
 * 并发下每枚令牌只会被取走一次，不会超额放行。
 */
public final class TokenBucketLimiter {

    private final long capacity;
    private final double refillTokensPerSecond;

    private record State(double tokens, long lastRefillMillis) {
    }

    private final AtomicReference<State> stateRef;

    public TokenBucketLimiter(long capacity, double refillTokensPerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("refillTokensPerSecond must be positive: " + refillTokensPerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        // 初始桶满：允许服务启动后的首个突发。
        this.stateRef = new AtomicReference<>(new State(capacity, -1L));
    }

    /** 尝试取走 1 枚令牌；成功放行，桶空拒绝。 */
    public boolean tryAcquire(long nowMillis) {
        return acquire(nowMillis).allowed();
    }

    /**
     * 一次原子取令牌的完整结论：allowed 与"取走后桶内剩余"来自同一次 CAS，
     * 结论与桶状态严格自洽。
     *
     * <p>被拒绝时 {@code retryAfterMillis} 按真实填充速率推算：
     * {@code ceil((1 - 当前可用令牌) / refillTokensPerSecond * 1000)}，
     * 即至少等到桶中补出第 1 枚令牌的时刻，绝不使用固定常量。
     */
    public AcquireResult acquire(long nowMillis) {
        while (true) {
            State snapshot = stateRef.get();
            long baseTime = snapshot.lastRefillMillis() < 0 ? nowMillis : snapshot.lastRefillMillis();
            double elapsedSeconds = Math.max(0L, nowMillis - baseTime) / 1000.0d;
            double refilled = Math.min(capacity,
                    snapshot.tokens() + elapsedSeconds * refillTokensPerSecond);
            if (refilled < 1.0d) {
                // 令牌不足：也要把填充进度落盘（仅当时间推进时），避免下一个线程重复计算；
                // 即使 CAS 失败也无所谓，重读再来。
                if (nowMillis > baseTime) {
                    stateRef.compareAndSet(snapshot, new State(refilled, nowMillis));
                }
                long waitMillis = (long) Math.ceil(
                        (1.0d - refilled) / refillTokensPerSecond * 1000.0d);
                return new AcquireResult(false, capacity - refilled, refilled,
                        Math.max(1L, waitMillis), capacity, refillTokensPerSecond);
            }
            double remaining = refilled - 1.0d;
            State next = new State(remaining, nowMillis);
            if (stateRef.compareAndSet(snapshot, next)) {
                return new AcquireResult(true, capacity - remaining, remaining,
                        0L, capacity, refillTokensPerSecond);
            }
            Thread.onSpinWait();
        }
    }

    /** 只读观测：读取当前桶的一致快照（惰性填充仅用于计算，不写回状态），不消耗令牌。 */
    public BucketView peek(long nowMillis) {
        State snapshot = stateRef.get();
        long baseTime = snapshot.lastRefillMillis() < 0 ? nowMillis : snapshot.lastRefillMillis();
        double elapsedSeconds = Math.max(0L, nowMillis - baseTime) / 1000.0d;
        double available = Math.min(capacity, snapshot.tokens() + elapsedSeconds * refillTokensPerSecond);
        return new BucketView(capacity, available, capacity - available, refillTokensPerSecond);
    }

    /** 仅供观测/测试：当前可用令牌数（触发一次惰性填充后），上限为容量。 */
    public double availableTokens(long nowMillis) {
        return peek(nowMillis).availableTokens();
    }

    /** 一次取令牌的完整结论（不可变）。remaining 为占用后桶内令牌；拒绝时 retryAfterMillis 为正。 */
    public record AcquireResult(boolean allowed, double used, double remaining,
                                long retryAfterMillis,
                                long capacity, double refillTokensPerSecond) {
    }

    /** 令牌桶只读视图：容量、当前可用、已占用、每秒填充速率。 */
    public record BucketView(long capacity, double availableTokens,
                             double usedTokens, double refillTokensPerSecond) {
    }
}
