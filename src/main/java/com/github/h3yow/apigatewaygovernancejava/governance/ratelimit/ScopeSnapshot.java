package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 单个维度（某个路由 key 或调用方 key）的配额快照。
 *
 * <p>{@code window} / {@code burst} 为 null 表示该口径未启用。
 * 累计计数语义：
 * <ul>
 *   <li>{@code totalAllowed}：该维度实际通过判定（并已占用对应配额/令牌）的请求总数；</li>
 *   <li>{@code totalRejected}：最终在该维度被拒绝的请求总数
 *       （在前序维度被短路的请求不计入本维度）。</li>
 * </ul>
 */
public record ScopeSnapshot(String key,
                            FixedWindowQuotaLimiter.WindowView window,
                            TokenBucketLimiter.BucketView burst,
                            long totalAllowed, long totalRejected) {
}
