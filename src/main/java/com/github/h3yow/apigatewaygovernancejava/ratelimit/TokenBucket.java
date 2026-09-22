package com.github.h3yow.apigatewaygovernancejava.ratelimit;

/**
 * 单维度令牌桶：窗口速率 + 突发容量。
 *
 * <p>语义：以 windowLimit/window 的恒定速率补充令牌，桶容量为
 * windowLimit + burstCapacity，因此窗口内稳态速率不超过 windowLimit/window，
 * 瞬时突发最多可超出 windowLimit 的 burstCapacity 个请求。</p>
 *
 * <p>线程安全：所有状态变更在实例锁内完成，基于时间戳惰性补充，
 * 并发下不会超额放行，也不会因并发而丢失或错算状态。</p>
 */
final class TokenBucket {

    private final double refillPerNanos;
    private final long capacity;

    private double tokens;
    private long lastRefillNanos;

    TokenBucket(RateLimitPolicy policy) {
        this.capacity = policy.windowLimit() + policy.burstCapacity();
        this.refillPerNanos = (double) policy.windowLimit() / policy.window().toNanos();
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 尝试消费一个令牌。
     *
     * @return 消费后的剩余令牌数（向下取整）；若不足则返回 -1 表示拒绝
     */
    synchronized long tryConsume() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return (long) tokens;
        }
        return -1;
    }

    /**
     * 估算距下一个令牌可用的毫秒数（用于 Retry-After）。
     */
    synchronized long millisUntilNextToken() {
        refill();
        if (tokens >= 1.0) {
            return 0;
        }
        double missing = 1.0 - tokens;
        return (long) Math.ceil(missing / refillPerNanos / 1_000_000.0);
    }

    synchronized long availableTokens() {
        refill();
        return (long) tokens;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed * refillPerNanos);
        lastRefillNanos = now;
    }
}
