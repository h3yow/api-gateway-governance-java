package com.github.h3yow.apigatewaygovernancejava.governance.downstream;

import com.github.h3yow.apigatewaygovernancejava.governance.retry.DownstreamErrorType;

/**
 * 下游调用失败的内部异常。携带归一化失败类型；
 * 内部细节（cause、地址等）仅记录日志，不直接透出给调用方。
 */
public class DownstreamException extends RuntimeException {

    private final DownstreamErrorType type;

    public DownstreamException(DownstreamErrorType type, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.type = type;
    }

    public DownstreamErrorType type() {
        return type;
    }
}
