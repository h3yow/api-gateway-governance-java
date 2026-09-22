package com.github.h3yow.apigatewaygovernancejava.governance.version;

import java.util.Set;

/**
 * 某能力某一版本的契约定义。
 *
 * @param version        版本号
 * @param status         生命周期状态
 * @param fields         该版本允许出现的全部字段名（未知字段一律拒绝，不静默忽略）
 * @param requiredFields 必填字段
 * @param removedFields  相对历史版本被删除的字段（出现则明确报错，不静默丢弃）
 * @param renamedFields  字段改名映射：旧字段名 -&gt; 新字段名（出现旧字段则明确提示改名）
 * @param addedSince     相对上一版本新增的字段（仅文档/解释用途）
 */
public record VersionDefinition(ApiVersion version,
                                VersionStatus status,
                                Set<String> fields,
                                Set<String> requiredFields,
                                Set<String> removedFields,
                                java.util.Map<String, String> renamedFields,
                                Set<String> addedSince) {
}
