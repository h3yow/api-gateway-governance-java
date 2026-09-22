package com.github.h3yow.apigatewaygovernancejava.ratelimit;

/**
 * 限流判定结果。
 *
 * @param allowed         是否放行
 * @param reason          拒绝原因；放行时为 null
 * @param remainingTokens 判定后该维度剩余额度（取两个维度中较小者）
 * @param retryAfterMillis 建议的重试等待毫秒数；放行时为 0
 */
public record RateLimitDecision(boolean allowed, RejectReason reason,
                                long remainingTokens, long retryAfterMillis) {

    public static RateLimitDecision allowed(long remainingTokens) {
        return new RateLimitDecision(true, null, remainingTokens, 0);
    }

    public static RateLimitDecision rejected(RejectReason reason, long retryAfterMillis) {
        return new RateLimitDecision(false, reason, 0, retryAfterMillis);
    }
}
