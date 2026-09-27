package com.github.h3yow.apigatewaygovernancejava.governance.web;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceOrchestrator;
import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceRequest;
import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitSnapshot;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 *   <li>GET  /api/govern/rate-limits —— 只读配额快照（不占额度、不改计数）</li>
 * </ul>
 * 驱动请求头：X-Caller-Id / X-Api-Version / X-Idempotency-Key / X-Failure-Scenario。
 * 成功响应额外携带四个剩余额度头（两维度 × 两口径）。
 */
@RestController
@RequestMapping("/api/govern")
public class GovernanceController {

    private final GovernanceOrchestrator orchestrator;
    private final RateLimitService rateLimitService;

    public GovernanceController(GovernanceOrchestrator orchestrator,
                                RateLimitService rateLimitService) {
        this.orchestrator = orchestrator;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/orders")
    public ResponseEntity<GovernanceResponse> orders(
            @RequestHeader("X-Caller-Id") String callerId,
            @RequestHeader("X-Api-Version") String version,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Failure-Scenario", required = false) String failureScenario,
            @RequestBody(required = false) Map<String, Object> payload) {
        return respond(orchestrator.handle(build(callerId, "orders", "orders",
                version, idempotencyKey, failureScenario, true, payload)));
    }

    @PostMapping("/profiles")
    public ResponseEntity<GovernanceResponse> profiles(
            @RequestHeader("X-Caller-Id") String callerId,
            @RequestHeader("X-Api-Version") String version,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Failure-Scenario", required = false) String failureScenario,
            @RequestBody(required = false) Map<String, Object> payload) {
        return respond(orchestrator.handle(build(callerId, "profiles", "profiles",
                version, idempotencyKey, failureScenario, true, payload)));
    }

    /** 只读配额快照：当前各路由/调用方的窗口用量、突发余量、累计放行/拒绝。 */
    @GetMapping("/rate-limits")
    public RateLimitSnapshot rateLimitSnapshot() {
        return rateLimitService.snapshot();
    }

    private ResponseEntity<GovernanceResponse> respond(GovernanceResponse body) {
        HttpHeaders headers = new HttpHeaders();
        RateLimitDecision rate = body.rateLimit();
        addQuota(headers, "X-RateLimit-Route-Window-Remaining",
                "X-RateLimit-Route-Burst-Remaining", rate.route());
        addQuota(headers, "X-RateLimit-Caller-Window-Remaining",
                "X-RateLimit-Caller-Burst-Remaining", rate.caller());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private void addQuota(HttpHeaders headers, String windowHeader, String burstHeader,
                          RateLimitDecision.DimensionQuota quota) {
        if (quota == null) {
            return;
        }
        if (quota.windowRemaining() != null) {
            headers.add(windowHeader, Long.toString(quota.windowRemaining()));
        }
        if (quota.burstRemaining() != null) {
            headers.add(burstHeader, Long.toString((long) Math.floor(quota.burstRemaining())));
        }
    }

    private GovernanceRequest build(String callerId, String routeKey, String capability,
                                    String version, String idempotencyKey,
                                    String failureScenario, boolean write,
                                    Map<String, Object> payload) {
        if (callerId == null || callerId.isBlank()) {
            throw new GovernanceException(ErrorCode.MISSING_REQUIRED_HEADER,
                    "请求头 X-Caller-Id 不能为空，请提供调用方标识后重新发送");
        }
        if (version == null || version.isBlank()) {
            throw new GovernanceException(ErrorCode.MISSING_REQUIRED_HEADER,
                    "请求头 X-Api-Version 不能为空，请指定受支持的 API 版本后重新发送");
        }
        return new GovernanceRequest(callerId, routeKey, capability, version,
                idempotencyKey, write, failureScenario,
                payload == null ? Map.of() : payload);
    }
}
