package com.github.h3yow.apigatewaygovernancejava.governance.version;

/**
 * 版本解析结论：解析成功时携带定义；失败由 GovernanceException 表达。
 */
public record VersionResolution(VersionDefinition definition, boolean deprecated) {
}
