package com.github.h3yow.apigatewaygovernancejava.governance.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.QuotaReport;

/**
 * 统一错误响应体：只暴露稳定错误码、一级分类与面向调用方的说明，
 * 绝不包含内部堆栈、下游主机/端口等敏感细节。
 *
 * <p>新增字段（仅在有意义时出现，不改变既有字段）：
 * <ul>
 *   <li>{@code category}：rate_limited / client_error / server_error 三选一，
 *       让调用方稳定区分"等会重试 / 自己改请求 / 服务端故障"；</li>
 *   <li>{@code retryable}：是否允许<b>原样重试同一请求</b>（仅限流为 true）；</li>
 *   <li>{@code retryAfterSeconds}：限流时建议等待秒数（与 Retry-After 头一致）；</li>
 *   <li>{@code quota}：限流时两个维度两种口径的剩余额度快照。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String errorCode, String category, int httpStatus,
                            boolean retryable, Long retryAfterSeconds,
                            String message, QuotaReport quota) {

    public static ErrorResponse of(ErrorCode code, String safeMessage) {
        return new ErrorResponse(code.reasonCode(), code.category().wireName(),
                code.httpStatus(), code.retryAllowed(), null, safeMessage, null);
    }

    /** 限流拒绝：携带真实等待秒数与剩余额度。 */
    public static ErrorResponse rateLimited(ErrorCode code, String safeMessage,
                                            long retryAfterSeconds, QuotaReport quota) {
        return new ErrorResponse(code.reasonCode(), code.category().wireName(),
                code.httpStatus(), true, retryAfterSeconds, safeMessage, quota);
    }
}
