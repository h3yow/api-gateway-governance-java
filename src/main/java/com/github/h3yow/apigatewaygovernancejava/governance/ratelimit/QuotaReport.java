package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

/**
 * 一次请求在两个维度上的剩余额度快照。
 *
 * <p>无论放行还是拒绝都会返回；被短路拒绝时，未参与判定的维度以<b>只读</b>方式取同一时点
 * 快照（不占用其任何计数）。所有数字都与该请求的判定结果同源、自洽：
 * 放行后展示的是本次已占用之后的余额，拒绝时对应口径的剩余必然不足 1。
 */
public record QuotaReport(ScopeQuota route, ScopeQuota caller) {
}
