package com.github.h3yow.apigatewaygovernancejava.governance.common;

/**
 * 治理层归一化错误码。每个错误码可映射到稳定的 HTTP 状态与对外可解释的原因，
 * 内部异常细节（堆栈、下游地址等）不会透出给调用方。
 */
public enum ErrorCode {

    RATE_LIMIT_ROUTE_WINDOW(429),
    RATE_LIMIT_ROUTE_BURST(429),
    RATE_LIMIT_CALLER_WINDOW(429),
    RATE_LIMIT_CALLER_BURST(429),
    VERSION_UNKNOWN(404),
    VERSION_DEPRECATED(400),
    VERSION_RETIRED(410),
    PAYLOAD_UNKNOWN_FIELD(400),
    PAYLOAD_FIELD_REMOVED(400),
    PAYLOAD_FIELD_RENAMED(400),
    PAYLOAD_MISSING_FIELD(400),
    DOWNSTREAM_TIMEOUT(504),
    DOWNSTREAM_CONNECTION_FAILED(502),
    DOWNSTREAM_BUSINESS_REJECTED(422),
    DOWNSTREAM_UNKNOWN_ERROR(500),
    IDEMPOTENCY_IN_FLIGHT(409),
    MISSING_IDEMPOTENCY_KEY(400);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 对外稳定的机器可读原因串，不包含任何内部细节。 */
    public String reasonCode() {
        return name().toLowerCase();
    }
}
