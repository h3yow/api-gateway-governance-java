package com.github.h3yow.apigatewaygovernancejava.governance.version;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;

/**
 * 语义化版本标识，仅取 major.minor（治理层不使用 patch 做路由区分）。
 * 接受 "v1"、"1"、"v1.2"、"1.2" 四种写法；空白/非法字符/越界一律明确拒绝，不猜测版本。
 * major 相同视为同一大版本线；新增字段只能出现在更高 minor，且保持向后兼容。
 */
public record ApiVersion(int major, int minor) implements Comparable<ApiVersion> {

    public static ApiVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GovernanceException(ErrorCode.VERSION_UNKNOWN,
                    "缺少 API 版本，请通过 X-Api-Version 指定（例如 v1.0）");
        }
        String text = raw.trim();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length < 1 || parts.length > 2) {
            throw new GovernanceException(ErrorCode.VERSION_UNKNOWN,
                    "无法识别的 API 版本: '" + raw + "'，期望形如 v1 或 v1.2");
        }
        int major = parsePart(parts[0], raw);
        int minor = parts.length == 2 ? parsePart(parts[1], raw) : 0;
        if (major < 0 || minor < 0) {
            throw new GovernanceException(ErrorCode.VERSION_UNKNOWN,
                    "无法识别的 API 版本: '" + raw + "'，版本号不得为负");
        }
        return new ApiVersion(major, minor);
    }

    private static int parsePart(String part, String raw) {
        if (!part.matches("\\d+")) {
            throw new GovernanceException(ErrorCode.VERSION_UNKNOWN,
                    "无法识别的 API 版本: '" + raw + "'，版本号必须为数字");
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ex) {
            throw new GovernanceException(ErrorCode.VERSION_UNKNOWN,
                    "无法识别的 API 版本: '" + raw + "'，版本号超出范围");
        }
    }

    @Override
    public int compareTo(ApiVersion o) {
        int byMajor = Integer.compare(this.major, o.major);
        return byMajor != 0 ? byMajor : Integer.compare(this.minor, o.minor);
    }

    public String asString() {
        return "v" + major + "." + minor;
    }
}
