package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 一次限流判定结论。
 *
 * <p>无论放行（200）还是被拒（429），结论都同时携带<b>两个维度</b>在<b>两种口径</b>下的
 * 剩余额度（{@link DimensionQuota}），区分两种口径：
 * <ul>
 *   <li><b>固定窗口配额</b>：{@code windowRemaining} —— 当前固定窗口内还剩多少次请求额度，
 *       窗口滚动后整体恢复为硬上限；被该口径拒绝时 {@code windowRetryAfterMillis}
 *       为距窗口结束的真实毫秒数；</li>
 *   <li><b>突发容量</b>：{@code burstRemaining} —— 令牌桶当时刻还可立即放行的请求数，
 *       按填充速率持续回补（上限为桶容量）；被该口径拒绝时 {@code burstRetryAfterMillis}
 *       为回补到 1 枚令牌的真实毫秒数。</li>
 * </ul>
 * 顶层 {@code retryAfterMillis} 只取<b>实际被拒口径</b>的真实等待，其他口径/维度的等待字段
 * 仅供信息展示，不会改变拒绝原因与等待时间。
 *
 * <p><b>实际占用口径 vs 只读展示口径</b>：判定顺序中真正走到并扣减了额度的口径，其数值
 * 取自同一次 CAS 结论——放行时为扣减后的余量，被拒时余量恒为 0/不足 1 枚令牌，不存在
 * “有余量却被拒/已用尽却放行”。因短路而未走到的口径（例如路由窗口先挡住时的路由突发与
 * 整个调用方维度）只做<b>只读投影</b>：按同一时刻的真实状态展示余量，不占用额度、
 * 不推进窗口或回补落盘、不改变任何计数、不影响拒绝原因与顶层等待时间；若该只读口径
 * 自身恰好也处于耗尽态，其等待字段按其真实恢复状态给出。字段为 null 仅表示该口径未启用。
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
     * @param windowRemaining        当前固定窗口剩余配额（非负整数）；本口径实际占用时为扣减后余量，
     *                               只读展示时为当时刻真实余量
     * @param windowRetryAfterMillis 本口径实际被拒时为距窗口滚动的真实毫秒数，放行时为 0；
     *                               只读展示且窗口恰好耗尽时为其自身真实恢复等待，否则为 0
     * @param burstRemaining         当前可立即使用的突发容量（令牌数，0~容量，可为小于 1 的小数）
     * @param burstRetryAfterMillis  本口径实际被拒时为回补到 1 枚令牌的真实毫秒数，放行时为 0；
     *                               只读展示且令牌不足 1 枚时为其自身真实回补等待，否则为 0
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
