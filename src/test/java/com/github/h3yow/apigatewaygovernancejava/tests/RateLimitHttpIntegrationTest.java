package com.github.h3yow.apigatewaygovernancejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP 全链路限流验证：独立测试上下文使用极小配额，
 * 验证超额请求返回 429 且 errorCode 能区分具体限流原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 调用方维度（默认）：窗口配额=2
                "governance.rate-limit.defaults.window-quota=2",
                "governance.rate-limit.defaults.window-millis=60000",
                "governance.rate-limit.defaults.burst-capacity=-1",
                // orders 路由：窗口配额=5（宽于调用方，使调用方维度先耗尽）
                "governance.rate-limit.routes.orders.window-quota=5",
                "governance.rate-limit.routes.orders.window-millis=60000",
                "governance.rate-limit.routes.orders.burst-capacity=-1"
        })
class RateLimitHttpIntegrationTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void noThrowingOnErrorStatus() {
        // 4xx/5xx 也要正常返回 ResponseEntity 供断言，而不是由客户端抛异常。
        rest.getRestTemplate().setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void overQuotaReturnsDistinguishable429() {
        String url = "http://localhost:" + port + "/api/govern/orders";
        // orders 路由在本 Spring 上下文内被多个测试方法共享，调用方必须唯一，
        // 才能稳定复现“调用方窗口(2)先耗尽、第 6 次路由窗口(5)耗尽”的完整序列。
        String caller = "caller-http-rl-" + Long.toHexString(System.nanoTime());
        int[] statuses = new int[6];
        String[] codes = new String[6];

        for (int i = 0; i < 6; i++) {
            HttpHeaders h = new HttpHeaders();
            h.set("X-Caller-Id", caller);
            h.set("X-Api-Version", "v2.0");
            h.set("X-Idempotency-Key", "idem-http-" + i + "-" + caller);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                    Map.of("itemId", "sku", "quantity", 1), h);
            // 用 Map 接收，兼容 200 的 GovernanceResponse 与 429 的 ErrorResponse。
            var resp = rest.exchange(url, HttpMethod.POST, entity, Map.class);
            statuses[i] = resp.getStatusCode().value();
            Map<String, Object> body = resp.getBody();
            codes[i] = body == null ? null : (String) body.get("errorCode");
        }

        // 判定顺序确定：每请求先过路由维度（通过即计入路由窗口），再过调用方维度。
        // 前2个：两维度均通过；第3~5个：调用方窗口(2)耗尽 -> caller_window，
        // 但每次都已先消耗路由窗口计数；第6个：路由窗口(5)也耗尽 -> route_window。
        // 两种 429 原因码各自可区分，正是"被拒绝请求必须能按原因区分"的端到端证据。
        assertEquals(200, statuses[0]);
        assertEquals(200, statuses[1]);
        assertEquals(429, statuses[2]);
        assertEquals(429, statuses[3]);
        assertEquals(429, statuses[4]);
        assertEquals(429, statuses[5]);
        assertEquals("rate_limit_caller_window", codes[2]);
        assertEquals("rate_limit_caller_window", codes[3]);
        assertEquals("rate_limit_caller_window", codes[4]);
        assertEquals("rate_limit_route_window", codes[5]);
        TestLogger.log("HTTP 限流拒绝",
                "同一调用方连续6次 POST /orders (调用方窗口=2, 路由窗口=5)",
                "路由先判(通过即计数)，调用方窗口先耗尽；第6个请求路由窗口也耗尽",
                "状态=" + java.util.Arrays.toString(statuses)
                        + " 原因码=" + java.util.Arrays.toString(codes));
    }
}
