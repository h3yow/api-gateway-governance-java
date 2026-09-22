package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 一次限流判定结论。allowed=false 时 reason 必填，调用方可据此区分拒绝原因。
 */
public record RateLimitDecision(boolean allowed, RateLimitRejectReason reason, String detail) {

    public static RateLimitDecision pass() {
        return new RateLimitDecision(true, null, "allowed");
    }

    public static RateLimitDecision reject(RateLimitRejectReason reason, String detail) {
        return new RateLimitDecision(false, reason, detail);
    }
}
