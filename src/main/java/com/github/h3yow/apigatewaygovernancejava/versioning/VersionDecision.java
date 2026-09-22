package com.github.h3yow.apigatewaygovernancejava.versioning;

import java.util.List;
import java.util.Set;

/**
 * 版本路由与字段校验结果。
 *
 * @param accepted      是否接受
 * @param version       命中的版本；未命中时为请求的原值
 * @param rejectReason  拒绝原因；接受时为 null
 * @param unknownFields 不认识的字段；无则为空集合
 * @param warnings      警告（如版本已弃用）；无则为空列表
 */
public record VersionDecision(boolean accepted, String version,
                              VersionRejectReason rejectReason,
                              Set<String> unknownFields,
                              List<String> warnings) {

    public static VersionDecision accepted(String version, List<String> warnings) {
        return new VersionDecision(true, version, null, Set.of(), warnings);
    }

    public static VersionDecision rejected(String version, VersionRejectReason reason, Set<String> unknownFields) {
        return new VersionDecision(false, version, reason, unknownFields, List.of());
    }
}
