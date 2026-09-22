package com.github.h3yow.apigatewaygovernancejava.resilience;

/**
 * 归一化后的下游调用异常。
 *
 * <p>只携带可安全暴露给调用方的信息；原始异常 cause 仅保留在服务端日志，
 * 不随错误响应透出。</p>
 */
public class DownstreamException extends RuntimeException {

    private final ErrorType errorType;

    public DownstreamException(ErrorType errorType, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
