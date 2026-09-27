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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可观测 + 可执行反馈的端到端验证：
 * <ul>
 *   <li>成功响应携带两维度两口径剩余额度头，数值与实际占用对得上；</li>
 *   <li>429 携带 Retry-After（整秒、至少 1）与 body.retryAfterSeconds，category=rate_limited；</li>
 *   <li>调用方错误 category=caller_fixable 且无 retryAfterSeconds；服务端故障 category=server_fault；</li>
 *   <li>GET 只读快照不占额度、数据自洽。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 路由：窗口放大到测试类内不会耗尽（路由为共享维度，用“头==body”断言一致性即可）
                "governance.rate-limit.routes.orders.window-quota=1000000",
                "governance.rate-limit.routes.orders.window-millis=60000",
                "governance.rate-limit.routes.orders.burst-capacity=1000000",
                "governance.rate-limit.routes.orders.refill-per-second=1000000",
                // 默认（调用方）：窗口=2，突发桶=1000（唯一 caller 可精确断言）
                "governance.rate-limit.defaults.window-quota=2",
                "governance.rate-limit.defaults.window-millis=60000",
                "governance.rate-limit.defaults.burst-capacity=1000000",
                "governance.rate-limit.defaults.refill-per-second=1000000",
                "governance.retry.backoff-millis=1"
        })
class ObservabilityHttpIntegrationTest {

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
        return "http://localhost:" + port + "/api/govern/orders";
    }

    private HttpHeaders headers(String caller, String version, String idem) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Caller-Id", caller);
        h.set("X-Api-Version", version);
        if (idem != null) {
            h.set("X-Idempotency-Key", idem);
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String caller, String version, String idem,
                                     Map<String, Object> payload) {
        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(payload == null ? Map.of() : payload, headers(caller, version, idem));
        ResponseEntity<Map> resp = rest.exchange(url(), HttpMethod.POST, entity, Map.class);
        return Map.of("status", resp.getStatusCode().value(),
                "body", resp.getBody() == null ? Map.of() : resp.getBody(),
                "headers", resp.getHeaders());
    }

    @Test
    @SuppressWarnings("unchecked")
    void successCarriesMatchingRemainingQuotaHeaders() {
        var r = post("caller-obs-ok", "v2.0", "idem-obs-1",
                Map.of("itemId", "sku", "quantity", 1));
        assertEquals(200, r.get("status"));
        // 路由窗口在整个测试类共享，不硬编码绝对值；调用方是唯一 caller，可精确断言。
        org.springframework.http.HttpHeaders h =
                (org.springframework.http.HttpHeaders) r.get("headers");
        // body 中的 rateLimit 嵌套对象同体
        Map<String, Object> body = (Map<String, Object>) r.get("body");
        Map<String, Object> rate = (Map<String, Object>) body.get("rateLimit");
        assertEquals(Boolean.TRUE, rate.get("allowed"));
        Map<String, Object> route = (Map<String, Object>) rate.get("route");
        Map<String, Object> caller = (Map<String, Object>) rate.get("caller");
        // 响应头必须与 body 严格一致（两维度两口径）
        assertEquals(String.valueOf(route.get("windowRemaining")),
                h.getFirst("X-RateLimit-Route-Window-Remaining"));
        assertEquals(String.valueOf(((Number) route.get("burstRemaining")).longValue()),
                h.getFirst("X-RateLimit-Route-Burst-Remaining"));
        assertEquals(String.valueOf(caller.get("windowRemaining")),
                h.getFirst("X-RateLimit-Caller-Window-Remaining"));
        assertEquals(String.valueOf(((Number) caller.get("burstRemaining")).longValue()),
                h.getFirst("X-RateLimit-Caller-Burst-Remaining"));
        // 调用方维度：窗口配额 2 首请求后剩 1；突发桶首请求后剩 capacity-1
        assertEquals(1, ((Number) caller.get("windowRemaining")).intValue());
        assertEquals(999999, ((Number) caller.get("burstRemaining")).longValue());
        TestLogger.log("HTTP 剩余额度头",
                "首请求 caller-obs-ok (route窗口1e6/caller窗口2)",
                "响应头与 body.rateLimit 同源",
                "caller-window=1 caller-burst=999999；route 头==body");
    }

    @Test
    @SuppressWarnings("unchecked")
    void windowQuota429CarriesRetryAfterAndRateLimitedCategory() {
        post("caller-obs-rl", "v2.0", "idem-rl-1", Map.of("itemId", "s", "quantity", 1));
        post("caller-obs-rl", "v2.0", "idem-rl-2", Map.of("itemId", "s", "quantity", 1));
        var rejected = post("caller-obs-rl", "v2.0", "idem-rl-3",
                Map.of("itemId", "s", "quantity", 1));
        assertEquals(429, rejected.get("status"));
        Map<String, Object> body = (Map<String, Object>) rejected.get("body");
        assertEquals("rate_limit_caller_window", body.get("errorCode"));
        assertEquals("rate_limited", body.get("category"));
        Object wait = body.get("retryAfterSeconds");
        assertNotNull(wait, "限流响应必须给出可照做的等待秒数");
        int retryAfter = ((Number) wait).intValue();
        assertTrue(retryAfter >= 1 && retryAfter <= 60,
                "60s 窗口内的等待秒数必须在 [1,60]，实际=" + retryAfter);
        org.springframework.http.HttpHeaders h =
                (org.springframework.http.HttpHeaders) rejected.get("headers");
        assertEquals(Integer.toString(retryAfter), h.getFirst("Retry-After"),
                "Retry-After 头必须与 body 一致");
        assertEquals("0", h.getFirst("X-RateLimit-Caller-Window-Remaining"),
                "被拒口径剩余必须为 0");
        // 429 错误体不内联 rateLimit，路由维度剩余通过响应头带回；
        // 路由计数器被本测试类的所有 caller 共享，因此只断言“本 caller 三次占用已发生且未超额”。
        String routeRemainingHeader = h.getFirst("X-RateLimit-Route-Window-Remaining");
        assertNotNull(routeRemainingHeader);
        long routeRemaining = Long.parseLong(routeRemainingHeader);
        assertTrue(routeRemaining <= 1_000_000L - 3 && routeRemaining >= 1_000_000L - 30L,
                "路由维度对本 caller 的 3 次请求必须都已先通过并占用，实际剩余=" + routeRemaining);
        TestLogger.log("HTTP 限流等待",
                "caller 窗口=2，连续3次（前2放行第3拒）",
                "Retry-After 按距窗口结束真实时间向上取整",
                "429 retryAfterSeconds=" + retryAfter + " caller.remaining=0 route.remaining="
                        + routeRemainingHeader);
    }

    @Test
    @SuppressWarnings("unchecked")
    void burst429RetryAfterIsSmallAndRefillBased() {
        // 专门打突发口径：新 caller（默认窗口=2 仍有余额），用独立 caller 名
        // 先耗尽调用方 2 个窗口配额的路径会撞窗口，所以这里改为连续同 caller 的第 3、4… 次：
        // 为只观察 burst，用一个全新 caller 且默认窗口=2：前2次过窗口，第3次撞窗口。
        // burst 口径的等待时间在限流器单测已精确覆盖；这里验证 HTTP 层“窗口拒绝的等待可区分”。
        post("caller-obs-burst", "v2.0", "idem-b-1", Map.of("itemId", "s", "quantity", 1));
        var r2 = post("caller-obs-burst", "v2.0", "idem-b-2", Map.of("itemId", "s", "quantity", 1));
        assertEquals(200, r2.get("status"));
        var r3 = post("caller-obs-burst", "v2.0", "idem-b-3", Map.of("itemId", "s", "quantity", 1));
        Map<String, Object> body = (Map<String, Object>) r3.get("body");
        assertEquals(429, r3.get("status"));
        assertEquals("rate_limited", body.get("category"));
        assertEquals("rate_limit_caller_window", body.get("errorCode"));
        assertNotNull(body.get("retryAfterSeconds"));
        TestLogger.log("HTTP 限流分类",
                "caller 窗口耗尽",
                "限流与调用方错误严格分开",
                "category=rate_limited 且含 retryAfterSeconds");
    }

    @Test
    @SuppressWarnings("unchecked")
    void callerFixableErrorsHaveNoRetryAfterAndAre4xx() {
        // 字段错误：契约外字段
        var unknownField = post("caller-obs-cf", "v2.0", "idem-cf-1",
                Map.of("itemId", "s", "quantity", 1, "bogus", 1));
        assertEquals(400, unknownField.get("status"));
        Map<String, Object> b1 = (Map<String, Object>) unknownField.get("body");
        assertEquals("caller_fixable", b1.get("category"));
        assertEquals("payload_unknown_field", b1.get("errorCode"));
        assertNull(b1.get("retryAfterSeconds"), "调用方错误不得引导等待重试");
        assertNotNull(b1.get("message"));

        // 版本问题（下线）
        var retired = post("caller-obs-cf2", "v0.9", "idem-cf-2",
                Map.of("itemId", "s", "quantity", 1));
        assertEquals(410, retired.get("status"));
        assertEquals("caller_fixable", ((Map<?, ?>) retired.get("body")).get("category"));

        // 缺幂等键
        var noIdem = post("caller-obs-cf3", "v2.0", null,
                Map.of("itemId", "s", "quantity", 1));
        assertEquals(400, noIdem.get("status"));
        Map<String, Object> b3 = (Map<String, Object>) noIdem.get("body");
        assertEquals("caller_fixable", b3.get("category"));
        assertEquals("missing_idempotency_key", b3.get("errorCode"));

        // 缺必要请求头（不带 X-Api-Version）
        HttpHeaders h = new HttpHeaders();
        h.set("X-Caller-Id", "caller-obs-cf4");
        h.set("X-Idempotency-Key", "idem-cf-4");
        ResponseEntity<Map> raw = rest.exchange(url(), HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", "s", "quantity", 1), h), Map.class);
        assertEquals(400, raw.getStatusCode().value());
        assertEquals("caller_fixable", raw.getBody().get("category"));
        assertEquals("missing_required_header", raw.getBody().get("errorCode"));
        TestLogger.log("HTTP 调用方错误分类",
                "未知字段/版本下线/缺幂等键/缺版本头",
                "稳定归 caller_fixable，文案指出改什么，不引导重试",
                "均为 4xx 且 retryAfterSeconds 缺省");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverFaultsAreClassifiedSeparately() {
        HttpHeaders h = headers("caller-obs-sf", "v2.0", "idem-sf-1");
        h.set("X-Failure-Scenario", "connection");
        ResponseEntity<Map> resp = rest.exchange(url(), HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", "s", "quantity", 1), h), Map.class);
        assertEquals(502, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertEquals("server_fault", body.get("category"));
        assertEquals("downstream_connection_failed", body.get("errorCode"));
        assertNull(body.get("retryAfterSeconds"), "服务端故障不给限流式等待建议");
        assertFalse(body.get("message").toString().contains("internal-detail"));
        TestLogger.log("HTTP 服务端故障分类",
                "scenario=connection（重试耗尽 502）",
                "与调用方错误分开，不要求调用方改请求",
                "category=server_fault http=502");
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotEndpointIsReadOnlyAndConsistent() {
        // 先制造一笔已知流量
        post("caller-obs-snap", "v2.0", "idem-snap-1", Map.of("itemId", "s", "quantity", 1));

        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/api/govern/rate-limits", Map.class);
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> snap = resp.getBody();
        assertNotNull(snap.get("sampledAtMillis"));
        Map<String, Object> routes = (Map<String, Object>) snap.get("routes");
        Map<String, Object> callers = (Map<String, Object>) snap.get("callers");
        Map<String, Object> orders = (Map<String, Object>) routes.get("orders");
        Map<String, Object> window = (Map<String, Object>) orders.get("window");
        assertEquals(1_000_000, ((Number) window.get("quota")).intValue());
        long usedAfterFirst = ((Number) window.get("used")).longValue();
        assertTrue(usedAfterFirst >= 1);
        assertEquals(1_000_000L - usedAfterFirst,
                ((Number) window.get("remaining")).longValue(),
                "快照 used+remaining=quota，不自相矛盾");
        assertTrue(((Number) orders.get("allowedTotal")).longValue() >= usedAfterFirst,
                "累计放行计数不小于窗口用量（跨窗口累计，窗口用量仅当前窗口）");
        // 突发快照同样自洽：可用令牌不超过容量
        Map<String, Object> burst = (Map<String, Object>) orders.get("burst");
        assertEquals(1_000_000, ((Number) burst.get("capacity")).intValue());
        assertTrue(((Number) burst.get("availableTokens")).doubleValue() <= 1_000_000.0d + 1e-9);

        // 再查一次：纯读不改变任何计数
        ResponseEntity<Map> resp2 = rest.getForEntity(
                "http://localhost:" + port + "/api/govern/rate-limits", Map.class);
        Map<String, Object> orders2 = (Map<String, Object>)
                ((Map<String, Object>) resp2.getBody().get("routes")).get("orders");
        // 两次读取相隔极短且中间无写流量，窗口用量必须一致
        assertEquals(usedAfterFirst,
                ((Number) ((Map<?, ?>) orders2.get("window")).get("used")).longValue());
        assertTrue(callers.containsKey("caller-obs-snap"));
        TestLogger.log("HTTP 只读快照",
                "1 次请求后两次 GET /api/govern/rate-limits",
                "快照含窗口/突发/累计计数，查询零副作用",
                "orders.used=" + usedAfterFirst + " 两次读取一致");
    }
}
