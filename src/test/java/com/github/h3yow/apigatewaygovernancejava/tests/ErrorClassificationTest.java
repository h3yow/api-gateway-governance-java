package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCategory;
import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 错误三分类的稳定性测试：
 * 限流恒为 rate_limited；凡是调用方自己能改好的（缺信息/版本/字段/幂等/在途/业务被拒）
 * 恒为 caller_fixable；服务端自身故障恒为 server_fault。分类不随 HTTP 状态巧合漂移。
 */
class ErrorClassificationTest {

    @Test
    void rateLimitCodesAreAlwaysRateLimited() {
        for (ErrorCode code : Set.of(ErrorCode.RATE_LIMIT_ROUTE_WINDOW,
                ErrorCode.RATE_LIMIT_ROUTE_BURST,
                ErrorCode.RATE_LIMIT_CALLER_WINDOW,
                ErrorCode.RATE_LIMIT_CALLER_BURST)) {
            assertEquals(429, code.httpStatus());
            assertEquals(ErrorCategory.RATE_LIMITED, code.category(),
                    code + " 必须归为限流类");
            assertEquals("rate_limited", code.category().wire());
        }
        TestLogger.log("错误分类-限流",
                "4 个限流错误码",
                "限流是暂时无额度而非请求错误",
                "全部 category=rate_limited http=429");
    }

    @Test
    void callerFixableCodesAreNeverMixedWithServerFaults() {
        Set<ErrorCode> callerFixable = Set.of(
                ErrorCode.VERSION_UNKNOWN,          // 版本不符合约定
                ErrorCode.VERSION_DEPRECATED,
                ErrorCode.VERSION_RETIRED,
                ErrorCode.PAYLOAD_UNKNOWN_FIELD,    // 字段越界/不符合契约
                ErrorCode.PAYLOAD_FIELD_REMOVED,
                ErrorCode.PAYLOAD_FIELD_RENAMED,
                ErrorCode.PAYLOAD_MISSING_FIELD,    // 缺必要信息
                ErrorCode.MISSING_IDEMPOTENCY_KEY,  // 写操作缺防重复标识
                ErrorCode.IDEMPOTENCY_IN_FLIGHT,    // 同键在途，调用方稍后凭键查询
                ErrorCode.DOWNSTREAM_BUSINESS_REJECTED, // 业务拒绝，结果确定
                ErrorCode.MISSING_REQUIRED_HEADER,  // 缺必要请求头
                ErrorCode.MALFORMED_REQUEST);       // 请求体非法
        for (ErrorCode code : callerFixable) {
            assertEquals(ErrorCategory.CALLER_FIXABLE, code.category(),
                    code + " 必须稳定归为调用方可修正，不得混入服务端故障");
            assertEquals("caller_fixable", code.category().wire());
            assertEquals(4, code.httpStatus() / 100,
                    code + " 必须是 4xx，不能引导调用方排查服务端");
        }
        TestLogger.log("错误分类-调用方可修正",
                "版本/字段/幂等/缺头/非法体/业务拒绝共 12 个错误码",
                "调用方改请求即可解决，不重试、不排查服务端",
                "全部 category=caller_fixable 且均为 4xx");
    }

    @Test
    void serverFaultCodesStayServerSide() {
        for (ErrorCode code : Set.of(ErrorCode.DOWNSTREAM_TIMEOUT,
                ErrorCode.DOWNSTREAM_CONNECTION_FAILED,
                ErrorCode.DOWNSTREAM_UNKNOWN_ERROR)) {
            assertEquals(ErrorCategory.SERVER_FAULT, code.category(),
                    code + " 必须归为服务端故障");
            assertEquals("server_fault", code.category().wire());
            assertEquals(5, code.httpStatus() / 100);
        }
        TestLogger.log("错误分类-服务端故障",
                "超时/连接失败/未知错误",
                "不是调用方改请求能解决的；重试由治理层内部完成",
                "全部 category=server_fault 且均为 5xx");
    }
}
