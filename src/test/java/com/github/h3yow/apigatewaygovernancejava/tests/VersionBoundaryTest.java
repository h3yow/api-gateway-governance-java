package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import com.github.h3yow.apigatewaygovernancejava.governance.version.ApiVersion;
import com.github.h3yow.apigatewaygovernancejava.governance.version.PayloadValidator;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionDefinition;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionRegistry;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionResolution;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionRoutingService;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本边界测试：未知/弃用/下线生命周期、字段新增向后兼容、
 * 字段删除/改名/未知字段/必填缺失的确定性拒绝。
 */
class VersionBoundaryTest {

    private VersionRegistry registry;
    private VersionRoutingService routing;
    private PayloadValidator validator;

    @BeforeEach
    void setUp() {
        registry = new VersionRegistry();
        routing = new VersionRoutingService(registry);
        validator = new PayloadValidator();

        registry.register("orders", new VersionDefinition(
                new ApiVersion(1, 0), VersionStatus.DEPRECATED,
                Set.of("itemId", "quantity", "promoCode"),
                Set.of("itemId", "quantity"),
                Set.of(), Map.of(), Set.of()));
        registry.register("orders", new VersionDefinition(
                new ApiVersion(1, 1), VersionStatus.ACTIVE,
                Set.of("itemId", "quantity", "promoCode", "contactPhone"),
                Set.of("itemId", "quantity"),
                Set.of(), Map.of(), Set.of("contactPhone")));
        registry.register("orders", new VersionDefinition(
                new ApiVersion(2, 0), VersionStatus.ACTIVE,
                Set.of("itemId", "quantity", "contactPhone", "discountCode"),
                Set.of("itemId", "quantity"),
                Set.of("promoCode"), Map.of("couponCode", "discountCode"),
                Set.of("discountCode")));
        registry.register("orders", new VersionDefinition(
                new ApiVersion(0, 9), VersionStatus.RETIRED,
                Set.of("itemId"), Set.of("itemId"), Set.of(), Map.of(), Set.of()));
    }

    @Test
    void addedFieldIsBackwardCompatible() {
        // 旧客户端只发 v1.0 的字段集合，访问 v1.1 仍然合法（新增字段非必填）。
        VersionResolution v11 = routing.resolve("orders", "v1.1");
        Map<String, Object> oldStylePayload = Map.of("itemId", "sku-1", "quantity", 2);
        assertNull(validator.validate(v11.definition(), oldStylePayload),
                "旧字段集合在含新增字段的新版本上必须继续通过");

        // 新客户端带新增字段也合法。
        Map<String, Object> newStylePayload =
                Map.of("itemId", "sku-1", "quantity", 2, "contactPhone", "13800000000");
        assertNull(validator.validate(v11.definition(), newStylePayload));

        TestLogger.log("新增字段兼容",
                "orders v1.1 新增 contactPhone；旧载荷={itemId,quantity}",
                "新增字段非必填，旧载荷字段集合是新版本字段集合的子集",
                "旧载荷 PASS，带新字段载荷 PASS（向后兼容成立）");
    }

    @Test
    void unknownVersionIsRejectedDeterministically() {
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> routing.resolve("orders", "v9.9"));
        assertEquals(ErrorCode.VERSION_UNKNOWN, ex.errorCode());
        assertEquals(404, ex.errorCode().httpStatus());
        TestLogger.log("未知版本",
                "orders v9.9（注册表中不存在）",
                "精确查询注册表，不做就近/静默路由",
                "拒绝 code=" + ex.errorCode().reasonCode() + " http=404");

        // 非法版本字符串同样按 VERSION_UNKNOWN 处理，绝不猜测。
        GovernanceException bad = assertThrows(GovernanceException.class,
                () -> routing.resolve("orders", "banana"));
        assertEquals(ErrorCode.VERSION_UNKNOWN, bad.errorCode());
        TestLogger.log("非法版本串",
                "orders banana",
                "版本解析仅接受 vN / vN.M 数字形式",
                "拒绝 code=" + bad.errorCode().reasonCode());
    }

    @Test
    void deprecatedVersionIsExplained() {
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> routing.resolve("orders", "v1.0"));
        assertEquals(ErrorCode.VERSION_DEPRECATED, ex.errorCode());
        assertEquals(400, ex.errorCode().httpStatus());
        assertTrue(ex.getMessage().contains("已弃用"), "弃用说明必须面向调用方可解释");
        TestLogger.log("弃用版本",
                "orders v1.0 (DEPRECATED)",
                "弃用即拒绝新调用，不静默转发到 v1.1/v2.0",
                "拒绝 code=" + ex.errorCode().reasonCode() + " message=" + ex.getMessage());
    }

    @Test
    void retiredVersionIsRejected() {
        GovernanceException ex = assertThrows(GovernanceException.class,
                () -> routing.resolve("orders", "v0.9"));
        assertEquals(ErrorCode.VERSION_RETIRED, ex.errorCode());
        assertEquals(410, ex.errorCode().httpStatus());
        TestLogger.log("下线版本",
                "orders v0.9 (RETIRED)",
                "下线是终态，精确返回 Gone",
                "拒绝 code=" + ex.errorCode().reasonCode() + " http=410");
    }

    @Test
    void removedRenamedAndUnknownFieldsAreRejected() {
        VersionDefinition v2 = routing.resolve("orders", "v2.0").definition();

        GovernanceException removed = validator.validate(v2,
                Map.of("itemId", "sku-1", "quantity", 1, "promoCode", "X"));
        assertEquals(ErrorCode.PAYLOAD_FIELD_REMOVED, removed.errorCode());
        assertTrue(removed.getMessage().contains("promoCode"));

        GovernanceException renamed = validator.validate(v2,
                Map.of("itemId", "sku-1", "quantity", 1, "couponCode", "C1"));
        assertEquals(ErrorCode.PAYLOAD_FIELD_RENAMED, renamed.errorCode());
        assertTrue(renamed.getMessage().contains("discountCode"),
                "改名拒绝必须明确指向新字段名");

        GovernanceException unknown = validator.validate(v2,
                Map.of("itemId", "sku-1", "quantity", 1, "hackerField", 7));
        assertEquals(ErrorCode.PAYLOAD_UNKNOWN_FIELD, unknown.errorCode());

        GovernanceException missing = validator.validate(v2,
                Map.of("itemId", "sku-1"));
        assertEquals(ErrorCode.PAYLOAD_MISSING_FIELD, missing.errorCode());
        assertTrue(missing.getMessage().contains("quantity"));

        TestLogger.log("不兼容字段拒绝",
                "orders v2.0 分别携带 已删除promoCode/旧名couponCode/未知hackerField/缺失quantity",
                "确定性顺序：删除→改名→未知→缺失，各自独立错误码",
                "removed=" + removed.errorCode() + ", renamed=" + renamed.errorCode()
                        + ", unknown=" + unknown.errorCode() + ", missing=" + missing.errorCode());
    }

    @Test
    void versionParsingAcceptsCommonForms() {
        assertEquals(new ApiVersion(2, 0), ApiVersion.parse("v2"));
        assertEquals(new ApiVersion(2, 0), ApiVersion.parse("2"));
        assertEquals(new ApiVersion(2, 1), ApiVersion.parse("v2.1"));
        assertEquals(new ApiVersion(2, 1), ApiVersion.parse("2.1"));
        assertFalse(ApiVersion.parse("v2.0").compareTo(ApiVersion.parse("v2.1")) > 0);
        TestLogger.log("版本号解析",
                "v2 / 2 / v2.1 / 2.1",
                "统一归一化为 major.minor，不猜测缺省含义以外的内容",
                "全部解析成功且 v2.0 < v2.1");
    }
}
