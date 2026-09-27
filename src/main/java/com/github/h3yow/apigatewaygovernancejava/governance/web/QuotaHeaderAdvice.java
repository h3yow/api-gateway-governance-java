package com.github.h3yow.apigatewaygovernancejava.governance.web;

import com.github.h3yow.apigatewaygovernancejava.governance.orchestration.GovernanceResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.QuotaReport;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.ScopeQuota;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 成功响应统一写入剩余额度响应头。调用方无需解析响应体即可在协议层读到
 * 「本次请求占用之后」的两维度两口径余额；未启用的口径不输出对应头。
 *
 * <ul>
 *   <li>{@code X-Route-Window-Remaining} / {@code X-Route-Burst-Remaining}</li>
 *   <li>{@code X-Caller-Window-Remaining} / {@code X-Caller-Burst-Remaining}</li>
 * </ul>
 * 窗口口径为非负整数；突发口径为令牌数（保留 3 位小数）。
 */
@RestControllerAdvice
public class QuotaHeaderAdvice implements ResponseBodyAdvice<Object> {

    public static final String ROUTE_WINDOW = "X-Route-Window-Remaining";
    public static final String ROUTE_BURST = "X-Route-Burst-Remaining";
    public static final String CALLER_WINDOW = "X-Caller-Window-Remaining";
    public static final String CALLER_BURST = "X-Caller-Burst-Remaining";

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // 对所有返回值放行；真正是否加头在 beforeBodyWrite 按 body 实际类型判断，
        // 避免声明类型为 Object/ResponseEntity 时被漏判。
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof GovernanceResponse gr && gr.accepted() && gr.rateLimit() != null) {
            QuotaReport quota = gr.rateLimit().quota();
            if (quota != null) {
                addQuotaHeaders(response, quota);
            }
        }
        return body;
    }

    private void addQuotaHeaders(ServerHttpResponse response, QuotaReport quota) {
        addScopeHeaders(response, quota.route(), ROUTE_WINDOW, ROUTE_BURST);
        addScopeHeaders(response, quota.caller(), CALLER_WINDOW, CALLER_BURST);
        // 让调用方/中间层可以直接读取这些自定义头。
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.getServletResponse().addHeader("Access-Control-Expose-Headers",
                    ROUTE_WINDOW + "," + ROUTE_BURST + "," + CALLER_WINDOW + "," + CALLER_BURST
                            + ",Retry-After");
        }
    }

    private void addScopeHeaders(ServerHttpResponse response, ScopeQuota scope,
                                 String windowHeader, String burstHeader) {
        if (scope == null) {
            return;
        }
        if (scope.window() != null) {
            response.getHeaders().add(windowHeader, Long.toString(scope.window().remaining()));
        }
        if (scope.burst() != null) {
            response.getHeaders().add(burstHeader,
                    String.format(java.util.Locale.ROOT, "%.3f", scope.burst().availableTokens()));
        }
    }
}
