package com.github.h3yow.apigatewaygovernancejava.governance.version;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 版本注册表：按「能力 key」维护各版本定义。
 *
 * <p>核心纪律：只做精确查询，<b>绝不做"就近路由"或静默回退</b>——
 * 调用方指定哪个版本就查哪个版本，查不到即由上层报 VERSION_UNKNOWN。
 */
@Component
public class VersionRegistry {

    private final Map<String, Map<ApiVersion, VersionDefinition>> capabilities = new ConcurrentHashMap<>();

    /** 注册某能力的一个版本定义；重复注册同一版本属于装配错误，立即失败。 */
    public void register(String capability, VersionDefinition definition) {
        Map<ApiVersion, VersionDefinition> versions =
                capabilities.computeIfAbsent(capability, k -> new ConcurrentHashMap<>());
        VersionDefinition previous = versions.putIfAbsent(definition.version(), definition);
        if (previous != null) {
            throw new IllegalStateException("能力 " + capability + " 的版本 "
                    + definition.version().asString() + " 被重复注册");
        }
    }

    /** 精确查询；不存在返回 empty，绝不回退到其他版本。 */
    public Optional<VersionDefinition> find(String capability, ApiVersion version) {
        Map<ApiVersion, VersionDefinition> versions = capabilities.get(capability);
        if (versions == null) {
            throw new GovernanceException(ErrorCode.VERSION_UNKNOWN,
                    "未知能力: " + capability);
        }
        return Optional.ofNullable(versions.get(version));
    }
}
