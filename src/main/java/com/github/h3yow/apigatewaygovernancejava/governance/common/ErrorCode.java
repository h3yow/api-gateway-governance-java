package com.github.h3yow.apigatewaygovernancejava.governance.common;

/**
 * 治理层归一化错误码。每个错误码可映射到稳定的 HTTP 状态与对外可解释的原因，
 * 并归属到稳定的 {@link ErrorCategory} 三分类（限流 / 调用方需修正 / 服务端故障），
 * 内部异常细节（堆栈、下游地址等）不会透出给调用方。
 */
public enum ErrorCode {

    RATE_LIMIT_ROUTE_WINDOW(429, ErrorCategory.RATE_LIMITED),
    RATE_LIMIT_ROUTE_BURST(429, ErrorCategory.RATE_LIMITED),
    RATE_LIMIT_CALLER_WINDOW(429, ErrorCategory.RATE_LIMITED),
    RATE_LIMIT_CALLER_BURST(429, ErrorCategory.RATE_LIMITED),
    VERSION_UNKNOWN(404, ErrorCategory.CALLER_FIXABLE),
    VERSION_DEPRECATED(400, ErrorCategory.CALLER_FIXABLE),
    VERSION_RETIRED(410, ErrorCategory.CALLER_FIXABLE),
    PAYLOAD_UNKNOWN_FIELD(400, ErrorCategory.CALLER_FIXABLE),
    PAYLOAD_FIELD_REMOVED(400, ErrorCategory.CALLER_FIXABLE),
    PAYLOAD_FIELD_RENAMED(400, ErrorCategory.CALLER_FIXABLE),
    PAYLOAD_MISSING_FIELD(400, ErrorCategory.CALLER_FIXABLE),
    DOWNSTREAM_TIMEOUT(504, ErrorCategory.SERVER_FAULT),
    DOWNSTREAM_CONNECTION_FAILED(502, ErrorCategory.SERVER_FAULT),
    DOWNSTREAM_BUSINESS_REJECTED(422, ErrorCategory.CALLER_FIXABLE),
    DOWNSTREAM_UNKNOWN_ERROR(500, ErrorCategory.SERVER_FAULT),
    IDEMPOTENCY_IN_FLIGHT(409, ErrorCategory.CALLER_FIXABLE),
    MISSING_IDEMPOTENCY_KEY(400, ErrorCategory.CALLER_FIXABLE),
    /** 调用方请求缺少必要头 / 必要信息（新增：稳定归为调用方可修正，而非 500）。 */
    MISSING_REQUIRED_HEADER(400, ErrorCategory.CALLER_FIXABLE),
    /** 请求体无法解析（JSON 非法等，新增：调用方可修正）。 */
    MALFORMED_REQUEST(400, ErrorCategory.CALLER_FIXABLE);

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

    /** 对外稳定的机器可读原因串，不包含任何内部细节。 */
    public String reasonCode() {
        return name().toLowerCase();
    }
}
