package com.github.h3yow.apigatewaygovernancejava.ratelimit;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 两维度（调用方 + 服务路由）限流器。
 *
 * <p>两个维度各自持有独立的令牌桶，任一维度耗尽即拒绝，拒绝原因可区分
 * （调用方配额/突发 vs 路由容量/突发）。</p>
 *
 * <p>并发语义：桶对象按键保存在 {@link ConcurrentHashMap} 中，
 * {@code compute} 保证同键桶的创建与消费原子完成，不同键之间无锁竞争；
 * 进程内并发不会导致状态丢失或错算，也不会超额放行。</p>
 */
public class RateLimiter {

    private final RateLimitPolicy callerPolicy;
    private final RateLimitPolicy routePolicy;
    private final Map<String, TokenBucket> callerBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> routeBuckets = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitPolicy callerPolicy, RateLimitPolicy routePolicy) {
        this.callerPolicy = Objects.requireNonNull(callerPolicy, "callerPolicy");
        this.routePolicy = Objects.requireNonNull(routePolicy, "routePolicy");
    }

    /**
     * 对一次调用进行限流判定。两个维度按"调用方优先"顺序判定，
     * 只有调用方维度放行时才消耗路由维度额度，避免误扣。
     *
     * @param callerId 调用方标识
     * @param routeId  服务路由标识
     * @return 判定结果；拒绝时携带可区分的原因与建议等待时间
     */
    public RateLimitDecision check(String callerId, String routeId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(routeId, "routeId");

        TokenBucket callerBucket = callerBuckets.computeIfAbsent(callerId, k -> new TokenBucket(callerPolicy));
        long callerRemaining = callerBucket.tryConsume();
        if (callerRemaining < 0) {
            RejectReason reason = callerPolicy.burstCapacity() > 0
                    ? RejectReason.CALLER_BURST_EXHAUSTED
                    : RejectReason.CALLER_QUOTA_EXHAUSTED;
            return RateLimitDecision.rejected(reason, callerBucket.millisUntilNextToken());
        }

        TokenBucket routeBucket = routeBuckets.computeIfAbsent(routeId, k -> new TokenBucket(routePolicy));
        long routeRemaining = routeBucket.tryConsume();
        if (routeRemaining < 0) {
            RejectReason reason = routePolicy.burstCapacity() > 0
                    ? RejectReason.ROUTE_BURST_EXHAUSTED
                    : RejectReason.ROUTE_CAPACITY_EXHAUSTED;
            return RateLimitDecision.rejected(reason, routeBucket.millisUntilNextToken());
        }

        return RateLimitDecision.allowed(Math.min(callerRemaining, routeRemaining));
    }
}
