package com.github.h3yow.apigatewaygovernancejava.resilience;

/**
 * 一次逻辑调用的最终结果。
 *
 * @param success     是否成功
 * @param value       成功时的返回值
 * @param errorType   失败时的归一化错误类型
 * @param safeMessage 可暴露给调用方的安全信息
 * @param attempts    实际尝试次数（含首次）
 */
public record RetryOutcome<T>(boolean success, T value, ErrorType errorType,
                              String safeMessage, int attempts) {

    public static <T> RetryOutcome<T> success(T value, int attempts) {
        return new RetryOutcome<>(true, value, null, null, attempts);
    }

    public static <T> RetryOutcome<T> failure(ErrorType errorType, String safeMessage, int attempts) {
        return new RetryOutcome<>(false, null, errorType, safeMessage, attempts);
    }
}
