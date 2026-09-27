package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCategory;
import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误分类契约单测：把错误稳定分到 rate_limited / client_error / server_error，
 * 且只有限流允许调用方原样重试。调用方问题与服务端故障严格不混。
 */
class ErrorClassificationTest {

    @Test
    void rateLimitCodesAreRateLimitedAndRetryable() {
        for (ErrorCode code : Set.of(
                ErrorCode.RATE_LIMIT_ROUTE_WINDOW, ErrorCode.RATE_LIMIT_ROUTE_BURST,
                ErrorCode.RATE_LIMIT_CALLER_WINDOW, ErrorCode.RATE_LIMIT_CALLER_BURST)) {
            assertEquals(429, code.httpStatus());
            assertEquals(ErrorCategory.RATE_LIMITED, code.category());
            assertTrue(code.retryAllowed(), "限流允许等待后原样重试: " + code);
            assertEquals("rate_limited", code.category().wireName());
        }
    }

    @Test
    void callerFixableCodesAreClientErrorAndNotRetryable() {
        Set<ErrorCode> callerFixable = Set.of(
                ErrorCode.VERSION_UNKNOWN, ErrorCode.VERSION_DEPRECATED,
                ErrorCode.VERSION_RETIRED,
                ErrorCode.PAYLOAD_UNKNOWN_FIELD, ErrorCode.PAYLOAD_FIELD_REMOVED,
                ErrorCode.PAYLOAD_FIELD_RENAMED, ErrorCode.PAYLOAD_MISSING_FIELD,
                ErrorCode.MISSING_IDEMPOTENCY_KEY, ErrorCode.IDEMPOTENCY_IN_FLIGHT,
                ErrorCode.DOWNSTREAM_BUSINESS_REJECTED);
        for (ErrorCode code : callerFixable) {
            assertEquals(ErrorCategory.CLIENT_ERROR, code.category(),
                    "调用方可自行修正的问题必须归 client_error: " + code);
            assertFalse(code.retryAllowed(),
                    "调用方问题不应引导原样重试，要先改请求: " + code);
            assertTrue(code.httpStatus() >= 400 && code.httpStatus() < 500,
                    "client_error 必须落在 4xx: " + code);
        }
    }

    @Test
    void transportAndInternalFaultsAreServerErrorAndNotRetryable() {
        for (ErrorCode code : Set.of(ErrorCode.DOWNSTREAM_TIMEOUT,
                ErrorCode.DOWNSTREAM_CONNECTION_FAILED,
                ErrorCode.DOWNSTREAM_UNKNOWN_ERROR)) {
            assertEquals(ErrorCategory.SERVER_ERROR, code.category());
            assertFalse(code.retryAllowed(),
                    "网关已按自身策略处理，结论不应让调用方再立即重试: " + code);
            assertTrue(code.httpStatus() >= 500, "server_error 必须落在 5xx: " + code);
            assertEquals("server_error", code.category().wireName());
        }
    }
}
