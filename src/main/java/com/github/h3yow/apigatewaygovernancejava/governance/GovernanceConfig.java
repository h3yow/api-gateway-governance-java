package com.github.h3yow.apigatewaygovernancejava.governance;

import com.github.h3yow.apigatewaygovernancejava.ratelimit.RateLimitPolicy;
import com.github.h3yow.apigatewaygovernancejava.ratelimit.RateLimiter;
import com.github.h3yow.apigatewaygovernancejava.resilience.IdempotencyStore;
import com.github.h3yow.apigatewaygovernancejava.resilience.RetryPolicy;
import com.github.h3yow.apigatewaygovernancejava.resilience.SafeRetryExecutor;
import com.github.h3yow.apigatewaygovernancejava.versioning.CapabilityVersion;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionRegistry;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionStatus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 治理组件装配。
 */
@Configuration
@EnableConfigurationProperties(GovernanceProperties.class)
public class GovernanceConfig {

    @Bean
    public RateLimiter rateLimiter(GovernanceProperties props) {
        RateLimitPolicy callerPolicy = new RateLimitPolicy(
                props.getCaller().getWindowLimit(),
                props.getCaller().getWindow(),
                props.getCaller().getBurstCapacity());
        RateLimitPolicy routePolicy = new RateLimitPolicy(
                props.getRoute().getWindowLimit(),
                props.getRoute().getWindow(),
                props.getRoute().getBurstCapacity());
        return new RateLimiter(callerPolicy, routePolicy);
    }

    /**
     * 演示能力 "orders" 的版本族：
     * v0 已下线；v1 已弃用；v2 为当前版本，仅相对 v1 新增 remark 字段（向后兼容）。
     */
    @Bean
    public VersionRegistry versionRegistry() {
        // 注册顺序即版本引入顺序：兼容门要求后注册的版本只能在已有在服版本上新增字段。
        // v1（弃用中）-> v2（当前，新增 remark）-> v0（已下线，不参与兼容门）。
        VersionRegistry registry = new VersionRegistry();
        registry.register(new CapabilityVersion("v1", VersionStatus.DEPRECATED,
                Set.of("name", "amount")));
        registry.register(new CapabilityVersion("v2", VersionStatus.ACTIVE,
                Set.of("name", "amount", "remark")));
        registry.register(new CapabilityVersion("v0", VersionStatus.SUNSET,
                Set.of("name", "amount")));
        return registry;
    }

    @Bean
    public IdempotencyStore idempotencyStore() {
        return new IdempotencyStore();
    }

    @Bean
    public SafeRetryExecutor safeRetryExecutor(GovernanceProperties props, IdempotencyStore store) {
        RetryPolicy readPolicy = new RetryPolicy(
                props.getRetry().getReadMaxAttempts(),
                props.getRetry().getBackoffMillis(),
                java.util.Set.of(com.github.h3yow.apigatewaygovernancejava.resilience.ErrorType.TIMEOUT,
                        com.github.h3yow.apigatewaygovernancejava.resilience.ErrorType.CONNECTION_FAILURE));
        RetryPolicy writePolicy = new RetryPolicy(
                props.getRetry().getWriteMaxAttempts(),
                props.getRetry().getBackoffMillis(),
                java.util.Set.of(com.github.h3yow.apigatewaygovernancejava.resilience.ErrorType.CONNECTION_FAILURE));
        return new SafeRetryExecutor(readPolicy, writePolicy, store);
    }
}
