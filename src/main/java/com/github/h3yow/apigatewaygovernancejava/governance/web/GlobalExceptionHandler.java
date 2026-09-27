package com.github.h3yow.apigatewaygovernancejava.governance.web;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCategory;
import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常归一化：
 * <ul>
 *   <li>治理异常按其稳定 ErrorCode 返回对应 HTTP 状态、机器可读原因与一级分类；</li>
 *   <li>限流（429）额外携带 {@code Retry-After}（建议等待整秒）与两维度剩余额度，
 *       调用方可直接照做；</li>
 *   <li>弃用版本除错误体外还携带 RFC 8594 风格的 Deprecation/Sunset/Warning 头，
 *       让生命周期结论在协议层也可解释；</li>
 *   <li>缺少必传请求头、请求体无法解析等"调用方改改就能好"的问题稳定归为
 *       client_error（400），并明确指出要补/改什么，绝不与服务端故障混淆；</li>
 *   <li>任何未预期异常统一脱敏为 500 DOWNSTREAM_UNKNOWN_ERROR，
 *       堆栈/主机/端口等内部细节只写日志，绝不进入响应体。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<ErrorResponse> handleGovernance(GovernanceException ex) {
        ErrorCode code = ex.errorCode();
        HttpHeaders headers = new HttpHeaders();

        ErrorResponse body;
        RateLimitDecision rate = ex.rateLimitDecision();
        if (rate != null && !rate.allowed()) {
            // 限流：等待时间基于被打满口径的真实恢复时刻；额度同源、自洽。
            long waitSeconds = rate.retryAfterSeconds();
            headers.add(HttpHeaders.RETRY_AFTER, Long.toString(waitSeconds));
            body = ErrorResponse.rateLimited(code, ex.getMessage(),
                    waitSeconds, rate.quota());
        } else {
            // 治理异常的 message 本身就是面向调用方的安全文案；cause 不透出。
            body = ErrorResponse.of(code, ex.getMessage());
        }

        if (code == ErrorCode.VERSION_DEPRECATED) {
            headers.add("Deprecation", "true");
            headers.add("Warning", "299 - \"API version deprecated; please upgrade to a supported version\"");
        }
        if (code.httpStatus() >= 500) {
            LOG.warn("治理异常(5xx): code={} message={}", code, ex.getMessage());
        }
        return new ResponseEntity<>(body, headers, HttpStatus.valueOf(code.httpStatus()));
    }

    /** 缺少必传请求头：调用方补上即可，明确指出缺哪个头，归 client_error(400)。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        String message = "缺少必传请求头 '" + ex.getHeaderName()
                + "'，请补充该请求头后重新提交，当前请求未被处理、也无需重试";
        return clientError(message);
    }

    /** 请求体缺失/非法 JSON 等：调用方修正请求体即可，归 client_error(400)。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        String message = "请求体缺失或不是合法 JSON，请检查 Content-Type 与 JSON 结构后重新提交，"
                + "当前请求未被处理、也无需原样重试";
        return clientError(message);
    }

    private ResponseEntity<ErrorResponse> clientError(String safeMessage) {
        // 对外仍给出稳定机器码与一级分类，但不暴露框架内部异常细节。
        ErrorResponse body = new ErrorResponse("invalid_request",
                ErrorCategory.CLIENT_ERROR.wireName(), 400, false, null, safeMessage, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // 完整细节仅记录在服务端日志。
        LOG.error("未预期异常，对外脱敏为 downstream_unknown_error", ex);
        ErrorResponse body = ErrorResponse.of(ErrorCode.DOWNSTREAM_UNKNOWN_ERROR,
                "服务内部错误，已记录日志，请求未被重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
