package com.github.h3yow.apigatewaygovernancejava.governance.retry;

/**
 * 重试策略：最大尝试次数（含首次）与退避基数（毫秒）。
 */
public record RetryPolicy(int maxAttempts, long backoffMillis) {

    public static RetryPolicy of(int maxAttempts, long backoffMillis) {
        return new RetryPolicy(maxAttempts, backoffMillis);
    }
}
