package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import java.util.Map;

/**
 * 限流配额的只读运行期快照（供运维 / 调用方查询）。
 *
 * <p>一致性语义：每个维度条目的全部字段取自同一时刻 {@code sampledAtMillis} 下、
 * 各限流器单个不可变状态的投影，不会读到“更新到一半”的状态；
 * 已用量不可能超过硬上限，窗口剩余与桶剩余恒为非负。查询本身不执行任何 CAS、
 * 不占用额度、不改变任何计数。
 */
public record RateLimitSnapshot(long sampledAtMillis,
                                Map<String, DimensionSnapshot> routes,
                                Map<String, DimensionSnapshot> callers) {

    /** 单个维度 key（路由或调用方）的快照。 */
    public record DimensionSnapshot(WindowSnapshot window,
                                    BurstSnapshot burst,
                                    long allowedTotal,
                                    long rejectedTotal) {
    }

    /** 固定窗口配额快照；该口径未启用时整个对象为 null。 */
    public record WindowSnapshot(long windowStart, long windowEnd,
                                 long quota, long used, long remaining) {
    }

    /** 令牌桶突发快照；该口径未启用时整个对象为 null。 */
    public record BurstSnapshot(long capacity, double refillPerSecond,
                                double availableTokens, double usedTokens) {
    }
}
