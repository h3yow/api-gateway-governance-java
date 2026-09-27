package com.github.h3yow.apigatewaygovernancejava.governance.common;

/**
 * 治理层归一化错误码。每个错误码可映射到稳定的 HTTP 状态与对外可解释的原因，
 * 内部异常细节（堆栈、下游地址等）不会透出给调用方。
 */
public enum ErrorCode {

    RATE_LIMIT_ROUTE_WINDOW(429, ErrorCategory.RATE_LIMITED),
    RATE_LIMIT_ROUTE_BURST(429, ErrorCategory.RATE_LIMITED),
    RATE_LIMIT_CALLER_WINDOW(429, ErrorCategory.RATE_LIMITED),
    RATE_LIMIT_CALLER_BURST(429, ErrorCategory.RATE_LIMITED),
    // 以下均为调用方修改请求即可解决的问题，与服务端故障严格区分。
    VERSION_UNKNOWN(404, ErrorCategory.CLIENT_ERROR),
    VERSION_DEPRECATED(400, ErrorCategory.CLIENT_ERROR),
    VERSION_RETIRED(410, ErrorCategory.CLIENT_ERROR),
    PAYLOAD_UNKNOWN_FIELD(400, ErrorCategory.CLIENT_ERROR),
    PAYLOAD_FIELD_REMOVED(400, ErrorCategory.CLIENT_ERROR),
    PAYLOAD_FIELD_RENAMED(400, ErrorCategory.CLIENT_ERROR),
    PAYLOAD_MISSING_FIELD(400, ErrorCategory.CLIENT_ERROR),
    // 服务端/下游自身故障：调用方改请求无法解决。
    DOWNSTREAM_TIMEOUT(504, ErrorCategory.SERVER_ERROR),
    DOWNSTREAM_CONNECTION_FAILED(502, ErrorCategory.SERVER_ERROR),
    // 业务拒绝是下游对"这份请求内容"的确定性结论，调用方需修改业务参数后重新提交。
    DOWNSTREAM_BUSINESS_REJECTED(422, ErrorCategory.CLIENT_ERROR),
    DOWNSTREAM_UNKNOWN_ERROR(500, ErrorCategory.SERVER_ERROR),
    // 同键在途属并发重复提交：调用方应凭同一幂等键查询，而不是立即重试。
    IDEMPOTENCY_IN_FLIGHT(409, ErrorCategory.CLIENT_ERROR),
    MISSING_IDEMPOTENCY_KEY(400, ErrorCategory.CLIENT_ERROR);

    private final int httpStatus;
    private final ErrorCategory category;

    ErrorCode(int httpStatus, ErrorCategory category) {
        this.httpStatus = httpStatus;
        this.category = category;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ErrorCategory category() {
        return category;
    }

    /**
     * 调用方在收到该结论后"原样重试同一请求"是否有意义：
     * <ul>
     *   <li>限流：有意义，但必须等到 Retry-After 之后；</li>
     *   <li>调用方错误：无意义，必须先按 message 修改请求；</li>
     *   <li>服务端故障：网关已按自身策略重试过，结论确定，调用方不应再立即重试。</li>
     * </ul>
     */
    public boolean retryAllowed() {
        return category == ErrorCategory.RATE_LIMITED;
    }

    /** 对外稳定的机器可读原因串，不包含任何内部细节。 */
    public String reasonCode() {
        return name().toLowerCase();
    }
}
