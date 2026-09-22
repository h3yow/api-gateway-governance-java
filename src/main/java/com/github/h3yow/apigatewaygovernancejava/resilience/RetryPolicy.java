package com.github.h3yow.apigatewaygovernancejava.resilience;

import java.util.Objects;
import java.util.Set;

/**
 * 重试策略。
 *
 * @param maxAttempts   最大尝试次数（含首次），>=1
 * @param backoffMillis 两次尝试之间的固定退避毫秒数，>=0
 * @param retryReadOn   读操作允许重试的错误类型集合
 */
public record RetryPolicy(int maxAttempts, long backoffMillis, Set<ErrorType> retryReadOn) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (backoffMillis < 0) {
            throw new IllegalArgumentException("backoffMillis must be >= 0");
        }
        retryReadOn = Set.copyOf(Objects.requireNonNull(retryReadOn, "retryReadOn"));
    }

    /** 读操作默认策略：超时与连接失败重试，业务拒绝与内部错误不重试。 */
    public static RetryPolicy defaultForReads() {
        return new RetryPolicy(3, 10,
                Set.of(ErrorType.TIMEOUT, ErrorType.CONNECTION_FAILURE));
    }

    /** 写操作默认策略：仅连接失败重试一次（连接失败表示请求确定未送达）。 */
    public static RetryPolicy defaultForWrites() {
        return new RetryPolicy(2, 10, Set.of(ErrorType.CONNECTION_FAILURE));
    }
}
