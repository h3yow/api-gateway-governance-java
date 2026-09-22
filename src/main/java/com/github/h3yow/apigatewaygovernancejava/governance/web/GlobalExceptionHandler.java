package com.github.h3yow.apigatewaygovernancejava.governance.web;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常归一化：
 * <ul>
 *   <li>治理异常按其稳定 ErrorCode 返回对应 HTTP 状态与机器可读原因；</li>
 *   <li>弃用版本除错误体外还携带 RFC 8594 风格的 Deprecation/Sunset/Warning 头，
 *       让生命周期结论在协议层也可解释；</li>
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // 完整细节仅记录在服务端日志。
        LOG.error("未预期异常，对外脱敏为 downstream_unknown_error", ex);
        ErrorResponse body = ErrorResponse.of(ErrorCode.DOWNSTREAM_UNKNOWN_ERROR,
                "服务内部错误，已记录日志，请求未被重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
