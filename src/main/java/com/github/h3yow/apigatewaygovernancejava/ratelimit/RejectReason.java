package com.github.h3yow.apigatewaygovernancejava.ratelimit;

/**
 * 限流拒绝原因，调用方可据此区分。
 */
public enum RejectReason {
    /** 调用方配额（窗口内速率）耗尽 */
    CALLER_QUOTA_EXHAUSTED,
    /** 调用方突发额度耗尽 */
    CALLER_BURST_EXHAUSTED,
    /** 服务路由容量（窗口内速率）耗尽 */
    ROUTE_CAPACITY_EXHAUSTED,
    /** 服务路由突发额度耗尽 */
    ROUTE_BURST_EXHAUSTED
}
