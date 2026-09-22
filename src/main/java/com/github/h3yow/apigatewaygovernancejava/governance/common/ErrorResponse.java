package com.github.h3yow.apigatewaygovernancejava.governance.common;

/**
 * 统一错误响应体：只暴露稳定错误码与面向调用方的说明，
 * 绝不包含内部堆栈、下游主机/端口等敏感细节。
 */
public record ErrorResponse(String errorCode, int httpStatus, String message) {

    public static ErrorResponse of(ErrorCode code, String safeMessage) {
        return new ErrorResponse(code.reasonCode(), code.httpStatus(), safeMessage);
    }
}
