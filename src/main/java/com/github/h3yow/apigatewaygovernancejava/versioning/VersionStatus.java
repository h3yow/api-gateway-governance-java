package com.github.h3yow.apigatewaygovernancejava.versioning;

/**
 * 版本生命周期状态。
 */
public enum VersionStatus {
    /** 正常服务 */
    ACTIVE,
    /** 已弃用：仍提供服务，但响应携带弃用警告 */
    DEPRECATED,
    /** 已下线：一律拒绝，不静默路由到其他版本 */
    SUNSET
}
