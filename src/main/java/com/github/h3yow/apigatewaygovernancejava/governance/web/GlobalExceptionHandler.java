package com.github.h3yow.apigatewaygovernancejava.governance.web;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常归一化：
 * <ul>
 *   <li>限流异常：429 + {@code Retry-After}（建议等待秒数，按真实限流状态推算）
 *       + 剩余额度响应头，分类恒为 {@code rate_limited}；</li>
 *   <li>调用方可修正问题（缺头、非法请求体、版本/字段/幂等等治理拒绝）：4xx，
 *       分类恒为 {@code caller_fixable}，文案明确指出要改什么，不引导重试或排查服务端；</li>
 *   <li>弃用版本额外携带 RFC 8594 风格的 Deprecation/Warning 头；</li>
 *   <li>任何未预期异常统一脱敏为 500 server_fault，
 *       堆栈/主机/端口等内部细节只写日志，绝不进入响应体。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RateLimitRejectedException ex) {
        RateLimitDecision d = ex.decision();
        ErrorCode code = d.reason().errorCode();
        // Retry-After 用“向上取整到整秒、至少 1 秒”，保证调用方睡够对应口径的恢复时间。
        long retryAfterSeconds = Math.max(1L, (d.retryAfterMillis() + 999L) / 1000L);
        ErrorResponse body = ErrorResponse.rateLimited(code, retryAfterSeconds,
                ex.getMessage() + "；请等待 " + retryAfterSeconds + " 秒后再试（无需修改请求）");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        addRemainingQuotaHeaders(headers, d);
        LOG.info("限流拒绝: reason={} retryAfterSeconds={}", code.reasonCode(), retryAfterSeconds);
        return new ResponseEntity<>(body, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    /** 把两种口径、两个维度的剩余额度写入响应头；null 字段表示该口径未启用/未判定，直接省略。 */
    private void addRemainingQuotaHeaders(HttpHeaders headers, RateLimitDecision d) {
        addQuota(headers, "X-RateLimit-Route-Window-Remaining",
                "X-RateLimit-Route-Burst-Remaining", d.route());
        addQuota(headers, "X-RateLimit-Caller-Window-Remaining",
                "X-RateLimit-Caller-Burst-Remaining", d.caller());
    }

    private void addQuota(HttpHeaders headers, String windowHeader, String burstHeader,
                          RateLimitDecision.DimensionQuota quota) {
        if (quota == null) {
            return;
        }
        if (quota.windowRemaining() != null) {
            headers.add(windowHeader, Long.toString(quota.windowRemaining()));
        }
        if (quota.burstRemaining() != null) {
            // 突发剩余向下取整展示（0.x 枚令牌意味着“已不能再放行”），避免误导调用方。
            headers.add(burstHeader, Long.toString((long) Math.floor(quota.burstRemaining())));
        }
    }

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<ErrorResponse> handleGovernance(GovernanceException ex) {
        ErrorCode code = ex.errorCode();
        // 治理异常的 message 本身就是面向调用方的安全文案；cause 不透出。
        ErrorResponse body = ErrorResponse.of(code, ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        if (code == ErrorCode.VERSION_DEPRECATED) {
            headers.add("Deprecation", "true");
            headers.add("Warning", "299 - \"API version deprecated; please upgrade to a supported version\"");
        }
        if (code.httpStatus() >= 500) {
            LOG.warn("治理异常(5xx): code={} message={}", code, ex.getMessage());
        }
        return new ResponseEntity<>(body, headers, HttpStatus.valueOf(code.httpStatus()));
    }

    /** 缺少必要请求头：调用方补上头即可，稳定归为 caller_fixable（而不是框架默认 500/400 裸错）。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        ErrorCode code = ErrorCode.MISSING_REQUIRED_HEADER;
        String message = "缺少必要请求头 '" + ex.getHeaderName()
                + "'，请补充该头后重新发送；这是请求侧问题，无需重试或等待";
        return ResponseEntity.badRequest().body(ErrorResponse.of(code, message));
    }

    /** 请求体无法解析（如非法 JSON）：调用方修正载荷格式后重发。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorCode code = ErrorCode.MALFORMED_REQUEST;
        String message = "请求体无法解析（不是合法的 JSON 或字段类型不符），请修正请求体后重新发送";
        return ResponseEntity.badRequest().body(ErrorResponse.of(code, message));
    }

    /** 参数类型不匹配等：同样是调用方可修正问题。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorCode code = ErrorCode.MALFORMED_REQUEST;
        String message = "请求参数 '" + ex.getName() + "' 的值不合法，请修正后重新发送";
        return ResponseEntity.badRequest().body(ErrorResponse.of(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // 完整细节仅记录在服务端日志。
        LOG.error("未预期异常，对外脱敏为 downstream_unknown_error", ex);
        ErrorResponse body = ErrorResponse.of(ErrorCode.DOWNSTREAM_UNKNOWN_ERROR,
                "服务内部错误，已记录日志；这是服务端故障，请求未被重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
