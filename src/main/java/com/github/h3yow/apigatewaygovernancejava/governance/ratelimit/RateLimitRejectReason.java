package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;

/**
 * 限流被拒绝时的可区分原因，覆盖两个维度（调用方 / 服务路由）
 * 与两种语义（固定窗口配额 / 令牌桶突发）。
 */
public enum RateLimitRejectReason {

    ROUTE_WINDOW_QUOTA(ErrorCode.RATE_LIMIT_ROUTE_WINDOW, "服务路由固定窗口配额已用尽"),
    ROUTE_BURST(ErrorCode.RATE_LIMIT_ROUTE_BURST, "服务路由突发流量超过令牌桶上限"),
    CALLER_WINDOW_QUOTA(ErrorCode.RATE_LIMIT_CALLER_WINDOW, "调用方固定窗口配额已用尽"),
    CALLER_BURST(ErrorCode.RATE_LIMIT_CALLER_BURST, "调用方突发流量超过令牌桶上限");

    private final ErrorCode errorCode;
    private final String description;

    RateLimitRejectReason(ErrorCode errorCode, String description) {
        this.errorCode = errorCode;
        this.description = description;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String description() {
        return description;
    }
}
