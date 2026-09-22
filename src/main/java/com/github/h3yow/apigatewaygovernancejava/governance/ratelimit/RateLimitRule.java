package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 单条限流规则，语义明确：
 * <ul>
 *   <li>windowQuota：每个固定窗口（windowMillis 毫秒）内最多放行的请求数（配额，硬上限）；</li>
 *   <li>burstCapacity：令牌桶容量，允许的瞬时突发上限；</li>
 *   <li>refillPerSecond：令牌桶每秒填充速率（稳态平均速率）。</li>
 * </ul>
 * 任一阈值为非正数时表示该语义不启用。
 */
public record RateLimitRule(long windowQuota, long windowMillis,
                            long burstCapacity, double refillPerSecond) {

    boolean windowEnabled() {
        return windowQuota > 0 && windowMillis > 0;
    }

    boolean burstEnabled() {
        return burstCapacity > 0 && refillPerSecond > 0;
    }
}
