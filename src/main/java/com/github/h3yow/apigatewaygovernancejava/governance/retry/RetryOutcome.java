package com.github.h3yow.apigatewaygovernancejava.governance.retry;

/**
 * 一次受治理下游调用的结果：业务值 + 实际尝试次数（含首次）。
 */
public record RetryOutcome<T>(T value, int attempts) {
}
