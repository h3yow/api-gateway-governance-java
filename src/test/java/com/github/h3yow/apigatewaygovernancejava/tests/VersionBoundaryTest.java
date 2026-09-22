package com.github.h3yow.apigatewaygovernancejava.tests;

import com.github.h3yow.apigatewaygovernancejava.versioning.CapabilityVersion;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionDecision;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionRegistry;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionRejectReason;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本生命周期与字段兼容边界测试。
 * 每个用例打印请求输入、判定依据与最终结果。
 */
class VersionBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(VersionBoundaryTest.class);

    private VersionRegistry registry() {
        VersionRegistry r = new VersionRegistry();
        r.register(new CapabilityVersion("v1", VersionStatus.DEPRECATED, Set.of("name", "amount")));
        r.register(new CapabilityVersion("v2", VersionStatus.ACTIVE, Set.of("name", "amount", "remark")));
        r.register(new CapabilityVersion("v0", VersionStatus.SUNSET, Set.of("name")));
        return r;
    }

    @Test
    void unknownVersionIsRejectedNotSilentlyRouted() {
        VersionRegistry r = registry();
        VersionDecision d = r.validate("v99", Set.of("name"));
        log.info("请求输入 version=v99 fields=[name]；判定依据：注册表无 v99；结果 accepted={} reason={}",
                d.accepted(), d.rejectReason());
        assertFalse(d.accepted());
        assertEquals(VersionRejectReason.UNKNOWN_VERSION, d.rejectReason());
        assertEquals("v99", d.version(), "必须保留请求原值，不静默改路由");
    }

    @Test
    void sunsetVersionIsRejected() {
        VersionRegistry r = registry();
        VersionDecision d = r.validate("v0", Set.of("name"));
        log.info("请求输入 version=v0 fields=[name]；判定依据：v0 状态 SUNSET；结果 accepted={} reason={}",
                d.accepted(), d.rejectReason());
        assertFalse(d.accepted());
        assertEquals(VersionRejectReason.VERSION_SUNSET, d.rejectReason());
    }

    @Test
    void deprecatedVersionStillServedWithWarning() {
        VersionRegistry r = registry();
        VersionDecision d = r.validate("v1", Set.of("name", "amount"));
        log.info("请求输入 version=v1 fields=[name,amount]；判定依据：v1 状态 DEPRECATED；结果 accepted={} warnings={}",
                d.accepted(), d.warnings());
        assertTrue(d.accepted());
        assertEquals(1, d.warnings().size());
    }

    @Test
    void renamedOrRemovedFieldIsRejectedNotSilentlyAccepted() {
        VersionRegistry r = registry();
        // v2 没有的字段：可能是删除、改名或从未存在，一律显式拒绝
        VersionDecision d = r.validate("v2", Set.of("name", "amount", "deleted_field"));
        log.info("请求输入 version=v2 fields=[name,amount,deleted_field]；判定依据：deleted_field 不在 v2 字段集；结果 accepted={} reason={} unknownFields={}",
                d.accepted(), d.rejectReason(), d.unknownFields());
        assertFalse(d.accepted());
        assertEquals(VersionRejectReason.UNKNOWN_FIELD, d.rejectReason());
        assertEquals(Set.of("deleted_field"), d.unknownFields());
    }

    @Test
    void newFieldInV2IsBackwardCompatibleForV2() {
        VersionRegistry r = registry();
        VersionDecision d = r.validate("v2", Set.of("name", "amount", "remark"));
        log.info("请求输入 version=v2 fields=[name,amount,remark]；判定依据：remark 为 v2 新增字段；结果 accepted={}",
                d.accepted());
        assertTrue(d.accepted());
        assertTrue(d.warnings().isEmpty());
    }

    @Test
    void registeringBreakingChangeFailsFast() {
        VersionRegistry r = new VersionRegistry();
        r.register(new CapabilityVersion("v1", VersionStatus.ACTIVE, Set.of("name", "amount")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> r.register(new CapabilityVersion("v2", VersionStatus.ACTIVE,
                        // amount 被删除/改名，name 保留
                        Set.of("name", "amount_renamed"))));
        log.info("请求输入：注册 v2 字段 [name,amount_renamed]；判定依据：v1 在服字段 amount 缺失；结果 注册被拒绝：{}",
                ex.getMessage());
        assertTrue(ex.getMessage().contains("amount"));
    }

    @Test
    void lifecycleTransitionToSunsetRejectsAfterwards() {
        VersionRegistry r = registry();
        assertTrue(r.validate("v2", Set.of("name")).accepted());
        r.updateStatus("v2", VersionStatus.SUNSET);
        VersionDecision d = r.validate("v2", Set.of("name"));
        log.info("请求输入 version=v2（状态已更新为 SUNSET）；判定依据：生命周期下线；结果 accepted={} reason={}",
                d.accepted(), d.rejectReason());
        assertFalse(d.accepted());
        assertEquals(VersionRejectReason.VERSION_SUNSET, d.rejectReason());
    }
}
