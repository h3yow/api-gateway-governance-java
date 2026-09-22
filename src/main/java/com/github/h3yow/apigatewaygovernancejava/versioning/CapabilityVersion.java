package com.github.h3yow.apigatewaygovernancejava.versioning;

import java.util.Objects;
import java.util.Set;

/**
 * 一个能力版本的注册信息。
 *
 * @param version     版本号，如 "v1"、"v2"
 * @param status      生命周期状态
 * @param knownFields 该版本请求体中合法的字段集合；
 *                    新增字段只出现在更高版本中，保证旧版本定义不变（向后兼容）
 */
public record CapabilityVersion(String version, VersionStatus status, Set<String> knownFields) {

    public CapabilityVersion {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(status, "status");
        knownFields = Set.copyOf(Objects.requireNonNull(knownFields, "knownFields"));
    }
}
