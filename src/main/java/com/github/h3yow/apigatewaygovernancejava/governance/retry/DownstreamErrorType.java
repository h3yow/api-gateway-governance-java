package com.github.h3yow.apigatewaygovernancejava.governance.retry;

/** 归一化后的下游失败类型，彼此可区分，决定是否可安全重试。 */
public enum DownstreamErrorType {
    /** 超时：可重试（需幂等保护）。 */
    TIMEOUT(true),
    /** 连接失败：可重试（需幂等保护）。 */
    CONNECTION_FAILED(true),
    /** 业务拒绝：语义确定，不重试。 */
    BUSINESS_REJECTED(false),
    /** 未归类内部错误：默认不重试，避免放大副作用。 */
    UNKNOWN(false);

    private final boolean retryable;

    DownstreamErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
