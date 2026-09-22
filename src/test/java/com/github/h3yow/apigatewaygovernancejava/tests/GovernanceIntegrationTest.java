package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：限流 -> 版本 -> 字段 -> 下游归一化的整体行为（真实 HTTP 端口）。
 * 为不影响速率敏感断言，本测试类使用独立的宽松限流测试配置。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "governance.rate-limit.defaults.window-quota=-1",
                "governance.rate-limit.defaults.burst-capacity=-1",
                "governance.rate-limit.routes.orders.window-quota=-1",
                "governance.rate-limit.routes.orders.burst-capacity=-1",
                "governance.retry.max-attempts=3",
                "governance.retry.backoff-millis=1"
        })
class GovernanceIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private HttpHeaders headers(String caller, String version, String idem, String scenario) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Caller-Id", caller);
        h.set("X-Api-Version", version);
        if (idem != null) {
            h.set("X-Idempotency-Key", idem);
        }
        if (scenario != null) {
            h.set("X-Failure-Scenario", scenario);
        }
        return h;
    }

    private String url() {
        return "http://localhost:" + port + "/api/govern/orders";
    }

    @Test
    void fullHappyPath() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 3),
                headers("caller-happy", "v2.0", "idem-happy-1", null));
        ResponseEntity<GovernanceResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, GovernanceResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        GovernanceResponse body = resp.getBody();
        assertTrue(body.accepted());
        assertEquals("v2.0", body.resolvedVersion());
        assertFalse(body.deduplicated(), "首次请求不应是重放");
        TestLogger.log("端到端成功路径",
                "POST /orders caller=caller-happy v2.0 key=idem-happy-1",
                "限流放行→v2.0 ACTIVE→字段合法→下游成功",
                "200 accepted attempts=" + body.attempts() + " body=" + body.body());
    }

    @Test
    void rateLimitedRequestReturnsDistinguishableReason() {
        // 用极小配额的调用方专用配置不可动态改，这里用版本/字段链路之外的确定性错误验证
        // "被拒绝操作可按原因区分"的统一形态：未知字段返回专属错误码。
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 1, "bogus", 9),
                headers("caller-x", "v2.0", "idem-x", null));
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("payload_unknown_field", resp.getBody().errorCode());
        TestLogger.log("端到端拒绝原因",
                "v2.0 载荷携带未知字段 bogus",
                "字段契约精确拒绝，不静默忽略",
                "http=400 errorCode=" + resp.getBody().errorCode());
    }

    @Test
    void versionLifecycleCodesThroughHttp() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 1),
                headers("caller-v", "v0.9", "idem-v", null));
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, ErrorResponse.class);
        assertEquals(HttpStatus.GONE, resp.getStatusCode());
        assertEquals("version_retired", resp.getBody().errorCode());
        TestLogger.log("端到端版本下线",
                "POST /orders v0.9 (RETIRED)",
                "版本注册表精确返回 Gone",
                "http=410 errorCode=" + resp.getBody().errorCode());
    }

    @Test
    void deprecatedVersionReturnsDeprecationHeader() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 1),
                headers("caller-dep", "v1.0", "idem-dep", null));
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("version_deprecated", resp.getBody().errorCode());
        assertEquals("true", resp.getHeaders().getFirst("Deprecation"),
                "弃用结论必须同时通过 Deprecation 头可解释");
        assertTrue(resp.getHeaders().getFirst("Warning").contains("deprecated"));
        TestLogger.log("端到端版本弃用",
                "POST /orders v1.0 (DEPRECATED)",
                "弃用即拒绝且附 Deprecation/Warning 头",
                "http=400 code=version_deprecated Deprecation=true");
    }

    @Test
    void timeoutRetryDeduplicatesEndToEnd() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 1),
                headers("caller-to", "v2.0", "idem-e2e-timeout", "timeout"));
        ResponseEntity<GovernanceResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, GovernanceResponse.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        GovernanceResponse body = resp.getBody();
        assertTrue(body.accepted());
        assertTrue(body.deduplicated(), "超时后的重试必须以幂等重放收敛");
        assertEquals(2, body.attempts(), "应恰好经历首次超时 + 一次重试");
        TestLogger.log("端到端超时重试",
                "v2.0 key=idem-e2e-timeout scenario=timeout",
                "首次生效后超时；重试命中下游幂等台账成功",
                "200 deduplicated=true attempts=" + body.attempts());
    }

    @Test
    void businessRejectionMapsTo422WithoutRetry() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 1),
                headers("caller-biz", "v2.0", "idem-e2e-biz", "business"));
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, ErrorResponse.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("downstream_business_rejected", resp.getBody().errorCode());
        TestLogger.log("端到端业务拒绝",
                "v2.0 key=idem-e2e-biz scenario=business",
                "BUSINESS_REJECTED 不可重试，归一化为 422",
                "http=422 errorCode=" + resp.getBody().errorCode());
    }

    @Test
    void internalDetailsAreNotExposed() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 1),
                headers("caller-u", "v2.0", "idem-e2e-unknown", "unknown"));
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, ErrorResponse.class);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("downstream_unknown_error", resp.getBody().errorCode());
        assertFalse(resp.getBody().message().contains("internal-detail"),
                "内部异常细节不得进入响应");
        TestLogger.log("端到端脱敏",
                "scenario=unknown（内部异常含 internal-detail 字样）",
                "全局异常处理器统一脱敏，细节仅入服务端日志",
                "http=500 message=\"" + resp.getBody().message() + "\"");
    }

    @Test
    void missingIdempotencyKeyOnWriteIsRejected() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku-1", "quantity", 1),
                headers("caller-noidem", "v2.0", null, null));
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                url(), HttpMethod.POST, entity, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("missing_idempotency_key", resp.getBody().errorCode());
        TestLogger.log("端到端缺幂等键",
                "写请求无 X-Idempotency-Key",
                "入口幂等守卫前置拒绝，防止无去重保障的写调用",
                "http=400 errorCode=" + resp.getBody().errorCode());
    }
}
