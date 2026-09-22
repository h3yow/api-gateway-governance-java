package com.github.h3yow.apigatewaygovernancejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 治理流水线端到端测试：HTTP 层验证限流、版本边界与错误归一化，
 * 并确认内部异常细节不透出。每个用例打印请求输入与最终结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GovernanceWebIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GovernanceWebIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    private String body(String route, String operation, String simulate, Map<String, Object> fields) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("routeId", route);
        m.put("operation", operation);
        m.put("idempotencyKey", "key-" + System.nanoTime());
        m.put("simulate", simulate);
        m.put("fields", fields);
        return json.writeValueAsString(m);
    }

    private MvcResult invoke(String caller, String version, String requestBody, int expectedStatus,
                             String expectedCode) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/governance/invoke")
                        .header("X-Caller-Id", caller)
                        .header("X-Api-Version", version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        log.info("请求输入 caller={} version={} body={}；判定依据期望 status={} code={}；最终结果 status={} body={}",
                caller, version, requestBody, expectedStatus, expectedCode,
                result.getResponse().getStatus(), content);
        if (expectedCode != null) {
            assertTrue(content.contains(expectedCode), "响应应包含错误码 " + expectedCode);
        }
        return result;
    }

    @Test
    void happyPathOnV2() throws Exception {
        String req = body("happy", "read", "ok", Map.of("name", "n", "amount", 1, "remark", "hi"));
        invoke("happy", "v2", req, 200, null);
    }

    @Test
    void deprecatedV1SucceedsButWithoutUnknownField() throws Exception {
        String req = body("orders", "read", "ok", Map.of("name", "n", "amount", 1));
        MvcResult r = invoke("dep", "v1", req, 200, null);
        assertTrue(r.getResponse().getContentAsString().contains("已弃用"));
    }

    @Test
    void unknownVersionReturns404() throws Exception {
        String req = body("orders", "read", "ok", Map.of("name", "n"));
        invoke("uv", "v99", req, 404, "VERSION_NOT_FOUND");
    }

    @Test
    void sunsetVersionReturns410() throws Exception {
        String req = body("orders", "read", "ok", Map.of("name", "n"));
        invoke("sun", "v0", req, 410, "VERSION_SUNSET");
    }

    @Test
    void unknownFieldReturns400() throws Exception {
        String req = body("orders", "read", "ok", Map.of("name", "n", "ghost", "x"));
        invoke("uf", "v2", req, 400, "UNKNOWN_FIELDS");
    }

    /**
     * 调用方默认窗口 10/s：第 11 个请求必须被限流，错误码可区分。
     */
    @Test
    void eleventhRequestFromSameCallerRateLimited() throws Exception {
        String req = body("orders", "read", "ok", Map.of("name", "n"));
        for (int i = 0; i < 10; i++) {
            invoke("burst-caller", "v2", req, 200, null);
        }
        MvcResult r = invoke("burst-caller", "v2", req, 429, "RATE_LIMITED:CALLER_QUOTA_EXHAUSTED");
        String content = r.getResponse().getContentAsString();
        assertTrue(content.contains("调用方窗口配额"));
    }

    @Test
    void downstreamTimeoutMapsTo504() throws Exception {
        String req = body("orders", "read", "timeout", Map.of("name", "n"));
        invoke("to", "v2", req, 504, "DOWNSTREAM:TIMEOUT");
    }

    @Test
    void connectionFailureMapsTo503() throws Exception {
        String req = body("orders", "read", "connect", Map.of("name", "n"));
        invoke("cf", "v2", req, 503, "DOWNSTREAM:CONNECTION_FAILURE");
    }

    @Test
    void businessRejectionMapsTo422() throws Exception {
        String req = body("orders", "read", "business", Map.of("name", "n"));
        MvcResult r = invoke("br", "v2", req, 422, "DOWNSTREAM:BUSINESS_REJECTION");
        assertTrue(r.getResponse().getContentAsString().contains("余额不足"));
    }

    @Test
    void internalErrorDoesNotLeakDetails() throws Exception {
        String req = body("orders", "read", "null", Map.of("name", "n"));
        // 下游调用内抛出的未预期异常被归一化为 INTERNAL（由重试执行器捕获）
        MvcResult r = invoke("ie", "v2", req, 500, "DOWNSTREAM:INTERNAL");
        String content = r.getResponse().getContentAsString();
        assertFalse(content.contains("SecretClient"), "内部类名不得透出: " + content);
        assertFalse(content.contains("internal detail"), "内部细节不得透出: " + content);
    }

    @Test
    void missingCallerHeaderReturns400() throws Exception {
        String req = body("orders", "read", "ok", Map.of("name", "n"));
        MvcResult result = mockMvc.perform(post("/api/governance/invoke")
                        .header("X-Api-Version", "v2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isBadRequest())
                .andReturn();
        log.info("请求输入=缺少 X-Caller-Id；最终结果 status={} body={}",
                result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("MISSING_HEADER"));
    }
}
