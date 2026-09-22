package com.github.h3yow.apigatewaygovernancejava.resilience;

/**
 * 下游调用失败的归一化错误类型。
 */
public enum ErrorType {
    /** 超时：下游已收到请求但结果未知（写操作不自动重试） */
    TIMEOUT,
    /** 连接失败：请求未送达下游（可安全重试） */
    CONNECTION_FAILURE,
    /** 业务拒绝：下游明确拒绝，结果确定（不重试） */
    BUSINESS_REJECTION,
    /** 内部错误：不透明，细节不透出给调用方（不重试） */
    INTERNAL
}
