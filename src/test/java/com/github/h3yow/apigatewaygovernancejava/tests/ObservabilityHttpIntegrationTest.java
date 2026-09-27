package com.github.h3yow.apigatewaygovernancejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证「可观测 + 可执行反馈」：
 * <ol>
 *   <li>429 带 Retry-After 头与 body(category=rate_limited, retryable=true,
 *       retryAfterSeconds>0, quota)；</li>
 *   <li>200 带 X-*-Remaining 剩余额度头，且 body.rateLimit.quota 与之同源；</li>
 *   <li>调用方错误稳定 category=client_error / retryable=false；服务端故障为 server_error；</li>
 *   <li>缺必传头 / 非法 JSON 归 invalid_request(client_error,400) 并说明要改什么；</li>
 *   <li>GET /api/govern/quotas 只读快照不消耗额度。</li>
 * </ol>
 *
 * <p>隔离策略：默认规则放开（不启用任何口径），只对 orders 路由与 429 专用调用方收紧；
 * 时间用 mock 的 {@link LongSupplier} 固定，使窗口恢复等待确定可断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 默认（普通调用方/profiles 等）：窗口 1000 + 桶 10，足够宽松不触发 429。
                "governance.rate-limit.defaults.window-quota=1000",
                "governance.rate-limit.defaults.window-millis=100000",
                "governance.rate-limit.defaults.burst-capacity=10",
                "governance.rate-limit.defaults.refill-per-second=1",
                // orders 路由：窗口配额1/窗口100s（时间固定为窗口起点 => 恢复等待恒为100s），桶容量3。
                "governance.rate-limit.routes.orders.window-quota=1",
                "governance.rate-limit.routes.orders.window-millis=100000",
                "governance.rate-limit.routes.orders.burst-capacity=3",
                "governance.rate-limit.routes.orders.refill-per-second=1",
                "governance.retry.max-attempts=1",
                "governance.retry.backoff-millis=1"
        })
class ObservabilityHttpIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    /** 真实可控时钟（非 mock，兼容 Java 25）：默认返回 0，使窗口起点/恢复等待确定。 */
    @Autowired
    ControlledClock controlledClock;

    /** 测试专用时钟 Bean：通过 AtomicLong 控制当前时刻。 */
    @org.springframework.context.annotation.Import({
            com.github.h3yow.apigatewaygovernancejava.Application.class,
            com.github.h3yow.apigatewaygovernancejava.governance.web.QuotaHeaderAdvice.class
    })
    @Configuration
    static class ClockConfig {
        @Bean
        ControlledClock controlledClock() {
            return new ControlledClock();
        }
    }

    /** 可被 RateLimitService 可选注入的时钟实现。 */
    static class ControlledClock implements LongSupplier {
        private final AtomicLong now = new AtomicLong(0L);

        void set(long millis) {
            now.set(millis);
        }

        @Override
        public long getAsLong() {
            return now.get();
        }
    }

    @BeforeEach
    void noThrow() {
        // 每个测试落在各自独立的 100s 窗口起点，orders 路由计数互不干扰。
        controlledClock.set(windowBaseMillis++ * 100_000L);
        rest.getRestTemplate().setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
    }

    /** 每个测试自增窗口序号，保证路由窗口互不重叠。 */
    private static int windowBaseMillis = 0;

    private String ordersUrl() {
        return "http://localhost:" + port + "/api/govern/orders";
    }

    private HttpHeaders baseHeaders(String caller, String idem) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Caller-Id", caller);
        h.set("X-Api-Version", "v2.0");
        if (idem != null) {
            h.set("X-Idempotency-Key", idem);
        }
        return h;
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedResponseCarriesRemainingQuotaHeadersAndBody() {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("itemId", "sku", "quantity", 1),
                baseHeaders("caller-obs-ok", "idem-obs-ok"));
        ResponseEntity<Map> resp = rest.exchange(ordersUrl(), HttpMethod.POST, entity, Map.class);
        assertEquals(200, resp.getStatusCode().value());

        // orders 路由窗口配额 1：本次占用后剩余 0。
        assertEquals("0", resp.getHeaders().getFirst("X-Route-Window-Remaining"));
        // 调用方走默认窗口 1000：占用 1 后剩 999。
        assertEquals("999", resp.getHeaders().getFirst("X-Caller-Window-Remaining"));
        // orders 突发桶容量 3：占用 1 枚后剩 2。
        assertEquals("2.000", resp.getHeaders().getFirst("X-Route-Burst-Remaining"));
        // 调用方默认桶容量 10：占用 1 枚后剩 9。
        assertEquals("9.000", resp.getHeaders().getFirst("X-Caller-Burst-Remaining"));

        Map<String, Object> body = resp.getBody();
        Map<String, Object> rate = (Map<String, Object>) body.get("rateLimit");
        Map<String, Object> quota = (Map<String, Object>) rate.get("quota");
        assertNotNull(quota, "成功体也携带 quota");
        Map<String, Object> route = (Map<String, Object>) quota.get("route");
        Map<String, Object> rw = (Map<String, Object>) route.get("window");
        assertEquals(0, ((Number) rw.get("remaining")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitedResponseCarriesRetryAfterAndQuota() {
        String caller = "caller-obs-429";
        // 第1个请求放行（窗口配额=1 用尽）。
        rest.exchange(ordersUrl(), HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", "sku", "quantity", 1),
                        baseHeaders(caller, "idem-429-1")), Map.class);
        // 第2个请求被路由窗口配额拒绝。
        ResponseEntity<Map> resp = rest.exchange(ordersUrl(), HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", "sku", "quantity", 1),
                        baseHeaders(caller, "idem-429-2")), Map.class);

        assertEquals(429, resp.getStatusCode().value());
        String retryAfter = resp.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 必须带 Retry-After 头");
        long wait = Long.parseLong(retryAfter);
        // 时钟固定在窗口起点、窗口 100s：首个请求占满后，第二个必等 100s 到窗口滚动。
        assertEquals(100L, wait, "等待必须等于窗口真实剩余时长，而非固定常量");

        Map<String, Object> body = resp.getBody();
        assertEquals("rate_limited", body.get("category"));
        assertEquals("rate_limit_route_window", body.get("errorCode"));
        assertEquals(Boolean.TRUE, body.get("retryable"), "仅限流允许原样重试");
        assertEquals(wait, ((Number) body.get("retryAfterSeconds")).longValue());
        Map<String, Object> quota = (Map<String, Object>) body.get("quota");
        assertNotNull(quota, "429 体必须携带剩余额度");
        Map<String, Object> route = (Map<String, Object>) quota.get("route");
        Map<String, Object> rw = (Map<String, Object>) route.get("window");
        assertEquals(0, ((Number) rw.get("remaining")).intValue(), "被拒口径剩余为0");
    }

    @Test
    void clientFixableProblemsAreClientErrorAndNotRetryable() {
        // 写操作缺幂等键：调用方可自行修正。
        ResponseEntity<Map> noIdem = rest.exchange(ordersUrl(), HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", "sku", "quantity", 1),
                        baseHeaders("caller-obs-ci", null)), Map.class);
        assertEquals(400, noIdem.getStatusCode().value());
        assertEquals("client_error", noIdem.getBody().get("category"));
        assertEquals(Boolean.FALSE, noIdem.getBody().get("retryable"));
        assertNull(noIdem.getBody().get("retryAfterSeconds"));

        // 已下线版本：调用方改版本即可。用不受窗口限制的 profiles 路由避免 orders 配额干扰。
        HttpHeaders retired = new HttpHeaders();
        retired.set("X-Caller-Id", "caller-obs-ci2");
        retired.set("X-Api-Version", "v0.9");
        retired.set("X-Idempotency-Key", "idem-ci-2");
        ResponseEntity<Map> retiredResp = rest.exchange(
                "http://localhost:" + port + "/api/govern/profiles",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", "u", "email", "a@b.c"), retired), Map.class);
        // profiles 未注册 v0.9 => 精确查不到版本（404 client_error），同样稳定归类。
        assertEquals(404, retiredResp.getStatusCode().value());
        assertEquals("client_error", retiredResp.getBody().get("category"));
        assertEquals(Boolean.FALSE, retiredResp.getBody().get("retryable"));

        // 未知字段：调用方修字段即可（profiles 路由不限流，专注字段分类）。
        HttpHeaders unknown = new HttpHeaders();
        unknown.set("X-Caller-Id", "caller-obs-ci3");
        unknown.set("X-Api-Version", "v1.1");
        unknown.set("X-Idempotency-Key", "idem-ci-3");
        ResponseEntity<Map> unknownField = rest.exchange(
                "http://localhost:" + port + "/api/govern/profiles",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", "u", "email", "a@b.c", "nope", 1), unknown),
                Map.class);
        assertEquals(400, unknownField.getStatusCode().value());
        assertEquals("client_error", unknownField.getBody().get("category"));
    }

    @Test
    void serverFaultIsServerErrorAndNotRetryableForCaller() {
        HttpHeaders h = baseHeaders("caller-obs-5xx", "idem-5xx");
        h.set("X-Failure-Scenario", "unknown");
        ResponseEntity<Map> unknown = rest.exchange(ordersUrl(), HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", "sku", "quantity", 1), h), Map.class);
        assertEquals(500, unknown.getStatusCode().value());
        assertEquals("server_error", unknown.getBody().get("category"));
        assertEquals(Boolean.FALSE, unknown.getBody().get("retryable"),
                "网关已尽力，结论不应引导调用方立即重试");
    }

    @Test
    void missingRequiredHeaderIsClientErrorWithActionableMessage() {
        // 不带 X-Caller-Id。
        HttpHeaders h = new HttpHeaders();
        h.set("X-Api-Version", "v2.0");
        h.set("X-Idempotency-Key", "idem-no-caller");
        ResponseEntity<Map> resp = rest.exchange(ordersUrl(), HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", "sku", "quantity", 1), h), Map.class);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("invalid_request", resp.getBody().get("errorCode"));
        assertEquals("client_error", resp.getBody().get("category"));
        assertTrue(String.valueOf(resp.getBody().get("message")).contains("X-Caller-Id"),
                "必须明确告诉调用方缺哪个头");
    }

    @Test
    void malformedJsonIsClientError() {
        HttpHeaders h = baseHeaders("caller-obs-badjson", "idem-badjson");
        h.set("Content-Type", "application/json");
        ResponseEntity<Map> resp = rest.exchange(ordersUrl(), HttpMethod.POST,
                new HttpEntity<>("{not-json", h), Map.class);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("client_error", resp.getBody().get("category"));
        assertEquals("invalid_request", resp.getBody().get("errorCode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaSnapshotEndpointIsReadOnlyAndReflectsCounters() {
        // 使用 orders 之外的独立路由，避免与同类其它用例在 orders 累计计数上互相影响。
        String profilesUrl = "http://localhost:" + port + "/api/govern/profiles";
        String snapshotUrl = "http://localhost:" + port + "/api/govern/quotas";

        ResponseEntity<Map> before = rest.getForEntity(snapshotUrl, Map.class);
        assertEquals(200, before.getStatusCode().value());

        HttpHeaders h = new HttpHeaders();
        h.set("X-Caller-Id", "caller-obs-snap");
        h.set("X-Api-Version", "v1.1");
        h.set("X-Idempotency-Key", "idem-snap-1");
        rest.exchange(profilesUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", "u1", "email", "a@b.c"), h), Map.class);

        ResponseEntity<Map> after1 = rest.getForEntity(snapshotUrl, Map.class);
        Map<String, Object> routes = (Map<String, Object>) after1.getBody().get("routes");
        Map<String, Object> profileSnap = (Map<String, Object>) routes.get("profiles");
        assertNotNull(profileSnap, "出现过的路由应在快照中");
        assertEquals(1, ((Number) profileSnap.get("totalAllowed")).intValue());

        // 再查一次快照，计数必须不变（只读）。
        ResponseEntity<Map> after2 = rest.getForEntity(snapshotUrl, Map.class);
        Map<String, Object> profileSnap2 =
                (Map<String, Object>) ((Map<String, Object>) after2.getBody().get("routes"))
                        .get("profiles");
        assertEquals(profileSnap.get("totalAllowed"), profileSnap2.get("totalAllowed"),
                "查询快照不得改变计数");
    }
}
