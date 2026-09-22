package com.github.h3yow.apigatewaygovernancejava.governance.downstream;

import java.util.Map;

/**
 * 发往下游的标准化请求。
 *
 * @param routeKey       路由/能力 key
 * @param idempotencyKey 写操作幂等键，可为 null（只读）
 * @param payload        业务字段
 * @param write          是否为写操作
 * @param failureScenario 测试/本地复现用：强制下游故障类型（timeout/connection/business/null）
 */
public record DownstreamRequest(String routeKey,
                                String idempotencyKey,
                                Map<String, Object> payload,
                                boolean write,
                                String failureScenario) {
}
