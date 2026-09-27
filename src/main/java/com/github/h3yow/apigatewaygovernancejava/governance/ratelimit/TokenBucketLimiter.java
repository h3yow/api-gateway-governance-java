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
     * 尝试取走 1 枚令牌，并原子带回结论：
     * <ul>
     *   <li>放行：{@code remaining} 为取走 1 枚令牌后、当时刻的剩余令牌数
     *       （已经过惰性填充，上限为容量，恒为非负）；</li>
     *   <li>拒绝：{@code remaining} 为当时刻桶内（不足 1 枚的）令牌数；
     *       {@code retryAfterMillis} 为按填充速率推算的“再填充到 1 枚令牌”所需毫秒
     *       {@code ceil((1-remaining)/rate*1000)}，随真实桶状态变化而非固定常量；</li>
     *   <li>拒绝不消耗令牌（填充进度仍会惰性落盘）。</li>
     * </ul>
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
                // 即使 CAS 失败也无所谓，重读再来。返回的剩余量按本次读到的快照投影计算，
                // 与桶的真实状态一致（任何成功落盘的状态只会更“新”，不会推翻该投影）。
                if (nowMillis > baseTime) {
                    stateRef.compareAndSet(snapshot, new State(refilled, nowMillis));
                }
                long waitMillis = (long) Math.ceil((1.0d - refilled) * 1000.0d / refillTokensPerSecond);
                return new AcquireResult(false, refilled, Math.max(1L, waitMillis));
            }
            State next = new State(refilled - 1.0d, nowMillis);
            if (stateRef.compareAndSet(snapshot, next)) {
                return new AcquireResult(true, Math.max(0.0d, refilled - 1.0d), 0L);
            }
            Thread.onSpinWait();
        }
    }

    /** 仅供观测/测试：当前可用令牌数（触发一次惰性填充后），上限为容量。 */
    public double availableTokens(long nowMillis) {
        return view(nowMillis).availableTokens();
    }

    /**
     * 只读快照：容量、填充速率、当时刻可用令牌与已占用令牌。
     * 纯读操作，不做 CAS、不改变桶状态。
     */
    public BurstView view(long nowMillis) {
        State snapshot = stateRef.get();
        long baseTime = snapshot.lastRefillMillis() < 0 ? nowMillis : snapshot.lastRefillMillis();
        double elapsedSeconds = Math.max(0L, nowMillis - baseTime) / 1000.0d;
        double available = Math.min(capacity, snapshot.tokens() + elapsedSeconds * refillTokensPerSecond);
        available = Math.max(0.0d, Math.min(capacity, available));
        double used = Math.max(0.0d, capacity - available);
        return new BurstView(capacity, refillTokensPerSecond, available, used);
    }

    public long capacity() {
        return capacity;
    }

    public double refillPerSecond() {
        return refillTokensPerSecond;
    }

    /** 单次取令牌的原子结论。remaining 为当时刻桶内剩余令牌（可能为 0~1 之间的小数）。 */
    public record AcquireResult(boolean allowed, double remaining, long retryAfterMillis) {
    }

    /** 令牌桶的只读快照（不可变）。 */
    public record BurstView(long capacity, double refillPerSecond,
                            double availableTokens, double usedTokens) {
    }
}
