package com.github.h3yow.apigatewaygovernancejava.governance.common;

import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;

/**
 * 治理层产生的、可对调用方解释的确定性异常。
 *
 * <p>限流拒绝可额外携带完整的 {@link RateLimitDecision}，供 Web 层透出
 * Retry-After 与剩余额度；其他场景该字段为 null。
 */
public class GovernanceException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient RateLimitDecision rateLimitDecision;

    public GovernanceException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public GovernanceException(ErrorCode errorCode, String message,
                               RateLimitDecision rateLimitDecision) {
        super(message);
        this.errorCode = errorCode;
        this.rateLimitDecision = rateLimitDecision;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 限流类异常携带的完整判定结论；非限流异常为 null。 */
    public RateLimitDecision rateLimitDecision() {
        return rateLimitDecision;
    }
}
