package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;

/**
 * 限流拒绝异常：除稳定 {@link ErrorCode} 与安全文案外，完整携带
 * {@link RateLimitDecision}（两维度剩余额度、按真实状态推算的等待时间），
 * 供 Web 层生成 Retry-After 与剩余额度响应头/响应体字段。
 */
public class RateLimitRejectedException extends RuntimeException {

    private final transient RateLimitDecision decision;

    public RateLimitRejectedException(RateLimitDecision decision) {
        super(decision.detail());
        if (decision.allowed()) {
            throw new IllegalArgumentException("only rejected decisions can be raised");
        }
        this.decision = decision;
    }

    public RateLimitDecision decision() {
        return decision;
    }

    public ErrorCode errorCode() {
        return decision.reason().errorCode();
    }
}
