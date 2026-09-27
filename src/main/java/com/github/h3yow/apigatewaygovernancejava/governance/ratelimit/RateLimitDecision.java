package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 一次限流判定结论。
 *
 * <p>除 allowed/reason/detail 外，结论同时携带本次请求在两个维度上的剩余额度
 * （{@link DimensionQuota}），区分两种口径：
 * <ul>
 *   <li><b>固定窗口配额</b>：{@code windowRemaining} —— 当前固定窗口内还剩多少次请求额度，
 *       窗口滚动后整体恢复为硬上限；被该口径拒绝时 {@code windowRetryAfterMillis}
 *       为距窗口结束的真实毫秒数；</li>
 *   <li><b>突发容量</b>：{@code burstRemaining} —— 令牌桶当时刻还可立即放行的请求数，
 *       按填充速率持续回补（上限为桶容量）；被该口径拒绝时 {@code burstRetryAfterMillis}
 *       为回补到 1 枚令牌的真实毫秒数。</li>
 * </ul>
 * 字段为 null 表示该口径未启用或该维度尚未判定（短路）。剩余额度与判定、实际占用同源，
 * 不存在“有余量却被拒/已用尽却放行”的矛盾。
 */
public record RateLimitDecision(boolean allowed, RateLimitRejectReason reason, String detail,
                                DimensionQuota route, DimensionQuota caller,
                                long retryAfterMillis) {

    public static RateLimitDecision pass(DimensionQuota route, DimensionQuota caller) {
        return new RateLimitDecision(true, null, "allowed", route, caller, 0L);
    }

    public static RateLimitDecision reject(RateLimitRejectReason reason, String detail,
                                           DimensionQuota route, DimensionQuota caller,
                                           long retryAfterMillis) {
        return new RateLimitDecision(false, reason, detail, route, caller, retryAfterMillis);
    }

    /**
     * 单个维度（服务路由 / 调用方）在两种口径下的剩余额度观测。
     *
     * @param windowRemaining        当前固定窗口剩余配额（非负整数）
     * @param windowRetryAfterMillis 窗口配额耗尽时距窗口滚动的毫秒数；放行时为 0
     * @param burstRemaining         当前可立即使用的突发容量（令牌数，0~容量，可为小于 1 的小数）
     * @param burstRetryAfterMillis  桶空时回补到 1 枚令牌的毫秒数；放行时为 0
     */
    public record DimensionQuota(Long windowRemaining,
                                 Long windowRetryAfterMillis,
                                 Double burstRemaining,
                                 Long burstRetryAfterMillis) {

        public static DimensionQuota ofWindow(long remaining, long retryAfterMillis) {
            return new DimensionQuota(remaining, retryAfterMillis, null, null);
        }

        public static DimensionQuota ofBurst(double remaining, long retryAfterMillis) {
            return new DimensionQuota(null, null, remaining, retryAfterMillis);
        }

        public static DimensionQuota of(long windowRemaining, long windowRetryAfterMillis,
                                        double burstRemaining, long burstRetryAfterMillis) {
            return new DimensionQuota(windowRemaining, windowRetryAfterMillis,
                    burstRemaining, burstRetryAfterMillis);
        }
    }
}
