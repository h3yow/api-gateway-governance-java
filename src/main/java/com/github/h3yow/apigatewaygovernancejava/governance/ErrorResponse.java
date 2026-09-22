package com.github.h3yow.apigatewaygovernancejava.governance;

/**
 * 统一错误响应：仅含错误码、可解释原因与建议等待时间，不含内部异常细节。
 */
public record ErrorResponse(String errorCode, String reason, Long retryAfterMillis) {
}
