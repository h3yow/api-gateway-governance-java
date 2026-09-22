package com.github.h3yow.apigatewaygovernancejava.governance.orchestration;

import java.util.Map;

/**
 * 进入治理层的标准化请求。
 *
 * @param callerId        调用方标识（限流维度之一）
 * @param routeKey        服务路由标识（限流维度之一）
 * @param capability      业务能力 key（版本注册维度）
 * @param version         调用方请求的 API 版本（X-Api-Version）
 * @param idempotencyKey  写操作幂等键（X-Idempotency-Key）
 * @param write           是否写操作
 * @param failureScenario 本地复现用的故障注入（X-Failure-Scenario），非业务字段
 * @param payload         业务字段（会按版本契约校验）
 */
public record GovernanceRequest(String callerId,
                                String routeKey,
                                String capability,
                                String version,
                                String idempotencyKey,
                                boolean write,
                                String failureScenario,
                                Map<String, Object> payload) {
}
