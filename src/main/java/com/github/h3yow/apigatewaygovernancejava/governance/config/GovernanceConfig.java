package com.github.h3yow.apigatewaygovernancejava.governance.config;

import com.github.h3yow.apigatewaygovernancejava.governance.version.ApiVersion;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionDefinition;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionRegistry;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionStatus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * 治理层配置入口：绑定限流/重试配置，并装配演示能力的多版本契约。
 *
 * <p>版本生命周期演示（orders 能力）：
 * <ul>
 *   <li>v1.0 DEPRECATED：仍在线但拒绝新调用（可解释的弃用策略）；</li>
 *   <li>v1.1 ACTIVE：相对 v1.0 新增 contactPhone（<b>只增字段，向后兼容</b>）；</li>
 *   <li>v2.0 ACTIVE：删除 promoCode、couponCode 改名为 discountCode（不兼容演进，
 *       旧字段出现即明确报错，绝不静默接受或路由到 v1）；</li>
 *   <li>v0.9 RETIRED：已下线，请求得到 410 Gone。</li>
 * </ul>
 *
 * <p>profiles 能力：v1.0 ACTIVE；v1.1 ACTIVE，新增 nickname 字段（向后兼容示范）。
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, RetryProperties.class})
public class GovernanceConfig {

    public GovernanceConfig(VersionRegistry registry) {
        registerOrders(registry);
        registerProfiles(registry);
    }

    private void registerOrders(VersionRegistry registry) {
        // v0.9 已下线
        registry.register("orders", new VersionDefinition(
                new ApiVersion(0, 9), VersionStatus.RETIRED,
                Set.of("itemId", "quantity"),
                Set.of("itemId", "quantity"),
                Set.of(), Map.of(), Set.of()));

        // v1.0 已弃用
        registry.register("orders", new VersionDefinition(
                new ApiVersion(1, 0), VersionStatus.DEPRECATED,
                Set.of("itemId", "quantity", "promoCode"),
                Set.of("itemId", "quantity"),
                Set.of(), Map.of(), Set.of()));

        // v1.1：仅新增 contactPhone，旧客户端请求（不带新字段）依然兼容
        registry.register("orders", new VersionDefinition(
                new ApiVersion(1, 1), VersionStatus.ACTIVE,
                Set.of("itemId", "quantity", "promoCode", "contactPhone"),
                Set.of("itemId", "quantity"),
                Set.of(), Map.of(), Set.of("contactPhone")));

        // v2.0：promoCode 删除，couponCode 改名为 discountCode
        registry.register("orders", new VersionDefinition(
                new ApiVersion(2, 0), VersionStatus.ACTIVE,
                Set.of("itemId", "quantity", "contactPhone", "discountCode"),
                Set.of("itemId", "quantity"),
                Set.of("promoCode"),
                Map.of("couponCode", "discountCode"),
                Set.of("discountCode")));
    }

    private void registerProfiles(VersionRegistry registry) {
        registry.register("profiles", new VersionDefinition(
                new ApiVersion(1, 0), VersionStatus.ACTIVE,
                Set.of("userId", "email"),
                Set.of("userId", "email"),
                Set.of(), Map.of(), Set.of()));

        // v1.1 新增 nickname：v1.0 客户端发旧字段集合在 v1.1 下仍完全合法
        registry.register("profiles", new VersionDefinition(
                new ApiVersion(1, 1), VersionStatus.ACTIVE,
                Set.of("userId", "email", "nickname"),
                Set.of("userId", "email"),
                Set.of(), Map.of(), Set.of("nickname")));
    }
}
