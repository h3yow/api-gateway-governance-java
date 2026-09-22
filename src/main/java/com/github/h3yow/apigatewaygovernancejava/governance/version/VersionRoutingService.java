package com.github.h3yow.apigatewaygovernancejava.governance.version;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import org.springframework.stereotype.Service;

/**
 * 版本解析入口：精确解析指定版本并做生命周期判定，不静默路由到其他版本。
 * <ul>
 *   <li>版本字符串非法或未注册 -&gt; VERSION_UNKNOWN（404）；</li>
 *   <li>已下线 RETIRED -&gt; VERSION_RETIRED（410 Gone），行为确定；</li>
 *   <li>已弃用 DEPRECATED -&gt; 默认拒绝（VERSION_DEPRECATED, 400），调用方必须显式选择受支持版本。</li>
 * </ul>
 */
@Service
public class VersionRoutingService {

    private final VersionRegistry registry;

    public VersionRoutingService(VersionRegistry registry) {
        this.registry = registry;
    }

    public VersionResolution resolve(String capability, String rawVersion) {
        ApiVersion version = ApiVersion.parse(rawVersion);
        VersionDefinition definition = registry.find(capability, version)
                .orElseThrow(() -> new GovernanceException(ErrorCode.VERSION_UNKNOWN,
                        "能力 " + capability + " 不存在版本 " + version.asString()
                                + "，不会静默路由到其他版本，请从受支持版本中选择"));
        return switch (definition.status()) {
            case ACTIVE -> new VersionResolution(definition, false);
            case DEPRECATED -> throw new GovernanceException(ErrorCode.VERSION_DEPRECATED,
                    "版本 " + version.asString() + " 已弃用并停止接受新调用，请升级到受支持版本");
            case RETIRED -> throw new GovernanceException(ErrorCode.VERSION_RETIRED,
                    "版本 " + version.asString() + " 已下线（Gone），不再提供服务");
        };
    }
}
