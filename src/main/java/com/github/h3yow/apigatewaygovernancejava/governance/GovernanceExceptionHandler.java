package com.github.h3yow.apigatewaygovernancejava.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误映射：未预期异常只记录在服务端日志，对外返回固定文案，
 * 内部异常细节（堆栈、内部类名、原始下游信息）一律不透出。
 */
@RestControllerAdvice
public class GovernanceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GovernanceExceptionHandler.class);

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        log.warn("缺少必需请求头: {}", e.getHeaderName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MISSING_HEADER",
                        "缺少必需请求头: " + e.getHeaderName(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 细节仅进日志；对外不携带 cause
        log.error("治理层捕获未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务内部错误，请联系管理员", null));
    }
}
