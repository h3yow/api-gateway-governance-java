package com.github.h3yow.apigatewaygovernancejava.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * 单维度限流策略：窗口语义 + 突发语义。
 *
 * @param windowLimit  每个窗口允许的最大请求数（速率语义）
 * @param window       窗口长度
 * @param burstCapacity 突发额度（令牌桶容量，可超过窗口速率的瞬时余量）
 */
public record RateLimitPolicy(long windowLimit, Duration window, long burstCapacity) {

    public RateLimitPolicy {
        Objects.requireNonNull(window, "window");
        if (windowLimit <= 0) {
            throw new IllegalArgumentException("windowLimit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (burstCapacity < 0) {
            throw new IllegalArgumentException("burstCapacity must be >= 0");
        }
    }
}
