package com.github.h3yow.apigatewaygovernancejava.governance.web;

import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceOrchestrator;
import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceRequest;
import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 治理层演示入口：
 * <ul>
 *   <li>POST /api/govern/orders   —— 订单写能力（多版本、需幂等键）</li>
 *   <li>POST /api/govern/profiles —— 资料能力（v1 写；v2 演示新增字段向后兼容）</li>
 * </ul>
 * 驱动请求头：X-Caller-Id / X-Api-Version / X-Idempotency-Key / X-Failure-Scenario。
 */
@RestController
@RequestMapping("/api/govern")
public class GovernanceController {

    private final GovernanceOrchestrator orchestrator;

    public GovernanceController(GovernanceOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/orders")
    public GovernanceResponse orders(@RequestHeader("X-Caller-Id") String callerId,
                                     @RequestHeader("X-Api-Version") String version,
                                     @RequestHeader(value = "X-Idempotency-Key", required = false)
                                     String idempotencyKey,
                                     @RequestHeader(value = "X-Failure-Scenario", required = false)
                                     String failureScenario,
                                     @RequestBody(required = false) Map<String, Object> payload) {
        return orchestrator.handle(build(callerId, "orders", "orders",
                version, idempotencyKey, failureScenario, true, payload));
    }

    @PostMapping("/profiles")
    public GovernanceResponse profiles(@RequestHeader("X-Caller-Id") String callerId,
                                       @RequestHeader("X-Api-Version") String version,
                                       @RequestHeader(value = "X-Idempotency-Key", required = false)
                                       String idempotencyKey,
                                       @RequestHeader(value = "X-Failure-Scenario", required = false)
                                       String failureScenario,
                                       @RequestBody(required = false) Map<String, Object> payload) {
        return orchestrator.handle(build(callerId, "profiles", "profiles",
                version, idempotencyKey, failureScenario, true, payload));
    }

    private GovernanceRequest build(String callerId, String routeKey, String capability,
                                    String version, String idempotencyKey,
                                    String failureScenario, boolean write,
                                    Map<String, Object> payload) {
        return new GovernanceRequest(callerId, routeKey, capability, version,
                idempotencyKey, write, failureScenario,
                payload == null ? Map.of() : payload);
    }
}
