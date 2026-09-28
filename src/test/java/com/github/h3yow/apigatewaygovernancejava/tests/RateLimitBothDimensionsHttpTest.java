package com.github.h3yow.apigatewaygovernancejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端验证修复：当请求先被<b>路由</b>维度拒掉时，429 响应仍必须同时带齐
 * 路由、调用方两个维度的剩余额度头；成功响应同样四维齐全。
 *
 * <p>独立 Spring 上下文：把 {@code profiles} 路由窗口压到 2（比默认调用方窗口 100 紧），
 * 因此同一全新调用方第 3 个请求稳定命中 {@code rate_limit_route_window}，
 * 此时调用方维度未被判定，只能只读展示，不得缺头、不得多扣、不得改分类与等待时间。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 默认（调用方）：窗口=100、无突发
                "governance.rate-limit.defaults.window-quota=100",
                "governance.rate-limit.defaults.window-millis=60000",
                "governance.rate-limit.defaults.burst-capacity=-1",
                // profiles 路由：窗口=2、无突发（本上下文独有，避免跨类共享路由计数）
                "governance.rate-limit.routes.profiles.window-quota=2",
                "governance.rate-limit.routes.profiles.window-millis=60000",
                "governance.rate-limit.routes.profiles.burst-capacity=-1"
        })
class RateLimitBothDimensionsHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void noThrowing() {
        rest.getRestTemplate().setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
    }

    private String url() {
        return "http://localhost:" + port + "/api/govern/profiles";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String caller, int i) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Caller-Id", caller);
        h.set("X-Api-Version", "v1.1");
        h.set("X-Idempotency-Key", "idem-http-both-" + i + "-" + caller);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                java.util.Map.of("userId", "u-1", "email", "a@example.com"), h);
        var resp = rest.exchange(url(), HttpMethod.POST, entity, Map.class);
        return Map.of("status", resp.getStatusCode().value(),
                "body", resp.getBody() == null ? Map.of() : resp.getBody(),
                "headers", resp.getHeaders());
    }

    @Test
    @SuppressWarnings("unchecked")
    void routeRejectionCarriesBothDimensionsAndStaysRateLimited() {
        String caller = "caller-http-routefirst";

        // 前 2 次：路由窗口=2 通过并占用，调用方窗口=100 同样通过。
        var r1 = post(caller, 1);
        var r2 = post(caller, 2);
        assertEquals(200, r1.get("status"));
        assertEquals(200, r2.get("status"));
        HttpHeaders h2 = (HttpHeaders) r2.get("headers");
        // 成功响应两维度窗口头齐全：路由剩 0（第 2 个占满），调用方剩 98（100-2）
        assertEquals("0", h2.getFirst("X-RateLimit-Route-Window-Remaining"));
        assertEquals("98", h2.getFirst("X-RateLimit-Caller-Window-Remaining"));

        // 第 3 次：路由窗口先挡住（修复前调用方维度整体不出现）。
        var r3 = post(caller, 3);
        assertEquals(429, r3.get("status"));
        Map<String, Object> body = (Map<String, Object>) r3.get("body");
        assertEquals("rate_limit_route_window", body.get("errorCode"),
                "补全调用方维度后仍必须归为路由限流，不能误归调用方错误/服务端故障");
        assertEquals("rate_limited", body.get("category"));
        assertNotNull(body.get("retryAfterSeconds"));

        HttpHeaders h3 = (HttpHeaders) r3.get("headers");
        assertNotNull(h3.getFirst("Retry-After"));
        // 被拒口径（路由窗口）：剩余 0
        assertEquals("0", h3.getFirst("X-RateLimit-Route-Window-Remaining"),
                "被路由限流时被拒口径剩余必须为 0");
        // 修复核心：调用方维度本次未判定、未扣额度，只读展示同一时刻真实余量（仍为 98）
        assertEquals("98", h3.getFirst("X-RateLimit-Caller-Window-Remaining"),
                "被路由挡住时调用方真实余量必须只读带回，且不被扣减（仍为 100-2=98）");

        // 再多打几次：始终被路由原因挡住；调用方余量头恒定 98（只读展示从不扣额度）。
        for (int i = 4; i <= 6; i++) {
            var rx = post(caller, i);
            assertEquals(429, rx.get("status"));
            Map<String, Object> bx = (Map<String, Object>) rx.get("body");
            assertEquals("rate_limit_route_window", bx.get("errorCode"),
                    "只读展示不改变后续请求结果，原因恒定为路由窗口");
            assertEquals("rate_limited", bx.get("category"));
            HttpHeaders hx = (HttpHeaders) rx.get("headers");
            assertEquals("0", hx.getFirst("X-RateLimit-Route-Window-Remaining"));
            assertEquals("98", hx.getFirst("X-RateLimit-Caller-Window-Remaining"),
                    "只读展示不多扣一次额度：调用方余量恒为 98");
        }

        // 快照交叉验证：调用方只真实放行过 2 次，拒绝计数为 0（只读展示不算它的拒绝）。
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = rest.getForEntity(
                "http://localhost:" + port + "/api/govern/rate-limits", Map.class).getBody();
        Map<String, Object> callers = (Map<String, Object>) snap.get("callers");
        Map<String, Object> cstate = (Map<String, Object>) callers.get(caller);
        assertNotNull(cstate, "调用方前 2 次真实出现过，应在快照中");
        Map<String, Object> cw = (Map<String, Object>) cstate.get("window");
        assertEquals(2, ((Number) cw.get("used")).intValue(),
                "被路由挡下后的只读展示不占用调用方窗口：used 恒为 2");
        assertEquals(98, ((Number) cw.get("remaining")).intValue());
        assertEquals(2L, ((Number) cstate.get("allowedTotal")).longValue());
        assertEquals(0L, ((Number) cstate.get("rejectedTotal")).longValue(),
                "调用方从未在自己的维度被拒，拒绝计数不得被只读展示虚增");

        TestLogger.log("HTTP 路由限流两维同体",
                "profiles 路由窗口=2，默认调用方窗口=100，同 caller 连打6次",
                "第3次起被路由限流；调用方维度只读带回真实余量98，不扣额度、不改分类",
                "429 errorCode=rate_limit_route_window category=rate_limited"
                        + " route=0 caller=98(只读) caller.used=2 rejectedTotal=0");
    }
}
