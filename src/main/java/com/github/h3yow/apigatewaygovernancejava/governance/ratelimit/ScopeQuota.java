package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 单个维度（服务路由 / 调用方）在一次请求结论时点的两种额度口径。
 *
 * <p>两种口径语义不同，调用方应分开理解：
 * <ul>
 *   <li><b>固定窗口配额 {@code window}</b>：当前固定窗口内的<b>硬上限</b>，
 *       {@code remaining} 为窗口滚动重置前还能放行的请求数；窗口一旦滚动，已用立即归零、
 *       剩余恢复为上限。恢复时刻见 {@code window.resetAtMillis()}。</li>
 *   <li><b>突发容量 {@code burst}</b>：令牌桶当前剩余的可瞬时放行令牌数
 *       （{@code availableTokens}，浮点），令牌按 {@code refillTokensPerSecond}
 *       持续回补（并非到某个固定时刻一次性恢复），桶满后新令牌丢弃、不会累积。</li>
 * </ul>
 * 任一字段为 {@code null} 表示该语义在配置上未启用。
 *
 * @param window 固定窗口配额口径（硬上限）；未启用为 null
 * @param burst  令牌桶突发口径（稳态+突发）；未启用为 null
 */
public record ScopeQuota(FixedWindowQuotaLimiter.WindowView window,
                         TokenBucketLimiter.BucketView burst) {
}
