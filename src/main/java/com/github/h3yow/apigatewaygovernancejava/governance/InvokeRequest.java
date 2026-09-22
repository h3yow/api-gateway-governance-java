package com.github.h3yow.apigatewaygovernancejava.governance;

import java.util.Map;

/**
 * 治理演示入口的请求体。
 *
 * @param routeId        服务路由标识
 * @param operation      read / write
 * @param idempotencyKey 写操作幂等键
 * @param simulate       模拟下游故障：ok/timeout/connect/business/null
 * @param fields         业务字段（参与版本字段兼容校验）
 */
public record InvokeRequest(String routeId, String operation, String idempotencyKey,
                            String simulate, Map<String, Object> fields) {
}
