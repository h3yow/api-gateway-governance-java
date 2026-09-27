package com.github.h3yow.apigatewaygovernancejava.governance.common;

/**
 * 统一错误响应体：只暴露稳定错误码、稳定分类与面向调用方的说明，
 * 绝不包含内部堆栈、下游主机/端口等敏感细节。
 *
 * @param errorCode         稳定机器可读错误码
 * @param httpStatus        HTTP 状态
 * @param category          稳定分类：rate_limited / caller_fixable / server_fault
 * @param retryAfterSeconds 仅 rate_limited 非空：建议等待多少秒后重试（按真实限流状态推算）
 * @param message           面向调用方的安全说明；caller_fixable 时明确指出要改什么
 */
public record ErrorResponse(String errorCode, int httpStatus, String category,
                            Long retryAfterSeconds, String message) {

    public static ErrorResponse of(ErrorCode code, String safeMessage) {
        return new ErrorResponse(code.reasonCode(), code.httpStatus(), code.category().wire(),
                null, safeMessage);
    }

    public static ErrorResponse rateLimited(ErrorCode code, long retryAfterSeconds,
                                            String safeMessage) {
        return new ErrorResponse(code.reasonCode(), code.httpStatus(),
                ErrorCategory.RATE_LIMITED.wire(),
                Math.max(1L, retryAfterSeconds), safeMessage);
    }
}
