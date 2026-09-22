package com.github.h3yow.apigatewaygovernancejava.governance.orchestration;

import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;

import java.util.Map;

/**
 * 治理结论：包含限流判定、版本信息、是否幂等重放与业务响应体。
 */
public record GovernanceResponse(boolean accepted,
                                 RateLimitDecision rateLimit,
                                 String resolvedVersion,
                                 boolean deprecated,
                                 boolean deduplicated,
                                 int attempts,
                                 Map<String, Object> body) {
}
