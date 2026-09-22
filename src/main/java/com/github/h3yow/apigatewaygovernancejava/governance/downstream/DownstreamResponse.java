package com.github.h3yow.apigatewaygovernancejava.governance.downstream;

import java.util.Map;

/**
 * 下游标准化响应。deduplicated=true 表示该结果来自幂等重放，写操作未重复生效。
 */
public record DownstreamResponse(boolean success,
                                 String reference,
                                 boolean deduplicated,
                                 Map<String, Object> body) {
}
