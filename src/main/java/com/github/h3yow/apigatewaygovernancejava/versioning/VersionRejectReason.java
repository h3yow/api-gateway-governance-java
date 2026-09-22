package com.github.h3yow.apigatewaygovernancejava.versioning;

/**
 * 版本校验拒绝原因，调用方可据此区分。
 */
public enum VersionRejectReason {
    /** 请求的版本未注册 */
    UNKNOWN_VERSION,
    /** 版本已下线 */
    VERSION_SUNSET,
    /** 请求包含该版本不认识的字段（已删除/已改名/从未存在） */
    UNKNOWN_FIELD
}
