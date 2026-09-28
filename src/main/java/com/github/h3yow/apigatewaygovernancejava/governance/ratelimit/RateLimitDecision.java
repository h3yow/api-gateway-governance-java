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
 * 字段为 null 仅表示该口径未启用。两个维度的结论始终同时携带：被实际判定的维度，
 * 剩余额度与判定、实际占用同源；因短路未实际判定的维度按同一时刻真实状态只读投影
 * （不占额度、不改计数），因此不存在“有余量却被拒/已用尽却放行”的矛盾，
 * 也不会出现某维度在结论里整体缺失的情况。
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
     * <p>实际判定的维度取自本次 CAS 结论；短路未判定的维度为同一时刻真实状态的
     * 只读投影（不占用额度、不改变计数），两种来源的语义一致。
     *
     * @param windowRemaining        当前固定窗口剩余配额（非负整数）
     * @param windowRetryAfterMillis 窗口配额耗尽时距窗口滚动的毫秒数；有余量时为 0
     * @param burstRemaining         当前可立即使用的突发容量（令牌数，0~容量，可为小于 1 的小数）
     * @param burstRetryAfterMillis  桶内不足 1 枚时回补到 1 枚令牌的毫秒数；有余量时为 0
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
