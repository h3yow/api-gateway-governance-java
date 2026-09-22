package com.github.h3yow.apigatewaygovernancejava.governance;

import java.util.List;

/**
 * 治理演示入口的响应。
 */
public record InvokeResponse(boolean accepted, String version, List<String> warnings,
                             Object result, String errorType, String message, int attempts) {
}
