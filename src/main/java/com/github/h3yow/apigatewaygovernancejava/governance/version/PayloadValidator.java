package com.github.h3yow.apigatewaygovernancejava.governance.version;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 按版本契约校验请求字段。任何不兼容都不被静默接受或静默丢弃：
 * <ul>
 *   <li>出现已删除字段 -&gt; PAYLOAD_FIELD_REMOVED，明确告知该字段在哪个版本被移除；</li>
 *   <li>出现改名字段（旧名）-&gt; PAYLOAD_FIELD_RENAMED，明确指向新字段名；</li>
 *   <li>出现契约外未知字段 -&gt; PAYLOAD_UNKNOWN_FIELD；</li>
 *   <li>必填字段缺失或为空 -&gt; PAYLOAD_MISSING_FIELD。</li>
 * </ul>
 * 检查顺序也是确定性的：删除 -&gt; 改名 -&gt; 未知 -&gt; 缺失，保证报错原因稳定。
 */
@Component
public class PayloadValidator {

    /** 校验通过返回 null；否则返回带可解释 ErrorCode 的异常。 */
    public GovernanceException validate(VersionDefinition definition, Map<String, Object> payload) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        String versionText = definition.version().asString();

        for (String key : body.keySet()) {
            if (definition.removedFields().contains(key)) {
                return new GovernanceException(ErrorCode.PAYLOAD_FIELD_REMOVED,
                        "字段 '" + key + "' 已在版本 " + versionText + " 中删除，请移除该字段后重试");
            }
            String newName = definition.renamedFields().get(key);
            if (newName != null) {
                return new GovernanceException(ErrorCode.PAYLOAD_FIELD_RENAMED,
                        "字段 '" + key + "' 已改名为 '" + newName + "'（自版本 " + versionText
                                + " 起），请使用新字段名");
            }
            if (!definition.fields().contains(key)) {
                return new GovernanceException(ErrorCode.PAYLOAD_UNKNOWN_FIELD,
                        "版本 " + versionText + " 不包含未知字段 '" + key + "'，请求不会被静默忽略");
            }
        }

        Set<String> required = definition.requiredFields();
        for (String key : required) {
            Object value = body.get(key);
            if (value == null || value instanceof String s && s.isBlank()) {
                return new GovernanceException(ErrorCode.PAYLOAD_MISSING_FIELD,
                        "版本 " + versionText + " 缺少必填字段 '" + key + "'");
            }
        }
        return null;
    }
}
