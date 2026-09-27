package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 一次限流判定结论。
 *
 * <p>除 allowed / reason / detail 外，还携带两类可执行反馈：
 * <ul>
 *   <li>{@link #quota()}：本请求在「服务路由」与「调用方」两个维度上、
 *       区分固定窗口配额与突发容量两种口径的剩余额度，与判定结果同源自洽；</li>
 *   <li>{@link #retryAfterMillis()}：被限流时基于当时真实状态推算出的建议等待时间
 *       （对应被打满的那一口径的恢复时间），放行时为 0。</li>
 * </ul>
 */
public record RateLimitDecision(boolean allowed, RateLimitRejectReason reason, String detail,
                                QuotaReport quota, long retryAfterMillis) {

    public static RateLimitDecision pass() {
        return new RateLimitDecision(true, null, "allowed", null, 0L);
    }

    /** 带完整反馈的放行结论：quota 为本次占用之后的余额。 */
    public static RateLimitDecision pass(QuotaReport quota) {
        return new RateLimitDecision(true, null, "allowed", quota, 0L);
    }

    public static RateLimitDecision reject(RateLimitRejectReason reason, String detail) {
        return new RateLimitDecision(false, reason, detail, null, 0L);
    }

    /**
     * 带完整反馈的拒绝结论。
     *
     * @param retryAfterMillis 基于被打满口径真实恢复时间推算的建议等待（毫秒，恒正）
     */
    public static RateLimitDecision reject(RateLimitRejectReason reason, String detail,
                                           QuotaReport quota, long retryAfterMillis) {
        return new RateLimitDecision(false, reason, detail, quota, retryAfterMillis);
    }

    /** 建议等待的整秒数（向上取整，保证至少等到恢复时刻），放行时为 0。 */
    public long retryAfterSeconds() {
        return retryAfterMillis <= 0 ? 0L : (retryAfterMillis + 999L) / 1000L;
    }
}
