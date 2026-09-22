package com.github.h3yow.apigatewaygovernancejava.governance.version;

/** 版本生命周期状态。 */
public enum VersionStatus {
    /** 在线正常服务。 */
    ACTIVE,
    /** 已弃用：仍可用，但响应会带 Deprecation/Warning 头并可按策略拒绝（默认拒绝新调用）。 */
    DEPRECATED,
    /** 已下线：确定拒绝，不静默路由到其他版本。 */
    RETIRED
}
