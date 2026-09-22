package com.github.h3yow.apigatewaygovernancejava.versioning;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力版本注册表：管理同一能力的多版本并行暴露与生命周期。
 *
 * <p>兼容规则：</p>
 * <ul>
 *   <li>新版本只能新增字段：按注册顺序（版本引入先后）注册时，新版本的字段集
 *       若删除/改名了此前已注册、仍在服务版本的字段，注册即被拒绝
 *       （fail-fast，防止破坏性变更进入运行时）。已下线版本不参与兼容门。</li>
 *   <li>未知版本显式拒绝，不做默认路由、不静默回退到其他版本。</li>
 *   <li>已下线（SUNSET）版本一律拒绝；弃用（DEPRECATED）版本仍服务但带警告。</li>
 *   <li>请求中出现该版本不认识的字段（字段删除、改名或从未存在）显式拒绝。</li>
 * </ul>
 *
 * <p>线程安全：注册表使用并发容器，注册与查询可并发进行。</p>
 */
public class VersionRegistry {

    private final Map<String, CapabilityVersion> versions = new ConcurrentHashMap<>();

    /**
     * 注册一个版本定义。
     *
     * @throws IllegalArgumentException 若版本号已存在，或新版本删除/改名了
     *                                  任一非下线版本仍存在的字段
     */
    public void register(CapabilityVersion capabilityVersion) {
        Objects.requireNonNull(capabilityVersion, "capabilityVersion");
        // 兼容门：新版本的字段集必须包含所有非下线版本的字段（只能新增，不能删除/改名）。
        // 新注册的版本自身若已下线（如补登记历史版本）则不参与兼容门。
        if (capabilityVersion.status() != VersionStatus.SUNSET) {
            Set<String> newFields = capabilityVersion.knownFields();
            for (CapabilityVersion existing : versions.values()) {
                if (existing.status() == VersionStatus.SUNSET) {
                    continue;
                }
                Set<String> removed = new LinkedHashSet<>(existing.knownFields());
                removed.removeAll(newFields);
                if (!removed.isEmpty()) {
                    throw new IllegalArgumentException(
                            "版本 " + capabilityVersion.version() + " 删除/改名了版本 "
                                    + existing.version() + " 仍在服务的字段 " + removed
                                    + "，违反向后兼容规则（只允许新增字段）");
                }
            }
        }
        versions.put(capabilityVersion.version(), capabilityVersion);
    }

    /** 更新版本生命周期状态（如 ACTIVE -> DEPRECATED -> SUNSET）。 */
    public void updateStatus(String version, VersionStatus status) {
        Objects.requireNonNull(status, "status");
        CapabilityVersion existing = versions.get(version);
        if (existing == null) {
            throw new IllegalArgumentException("未知版本: " + version);
        }
        versions.put(version, new CapabilityVersion(existing.version(), status, existing.knownFields()));
    }

    /** 查询版本定义。 */
    public Optional<CapabilityVersion> find(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    /**
     * 校验请求：版本必须已注册且未下线，请求字段必须全部属于该版本的已知字段。
     */
    public VersionDecision validate(String version, Set<String> requestFields) {
        Objects.requireNonNull(version, "version");
        Set<String> fields = requestFields == null ? Set.of() : requestFields;

        CapabilityVersion capabilityVersion = versions.get(version);
        if (capabilityVersion == null) {
            return VersionDecision.rejected(version, VersionRejectReason.UNKNOWN_VERSION, Set.of());
        }
        if (capabilityVersion.status() == VersionStatus.SUNSET) {
            return VersionDecision.rejected(version, VersionRejectReason.VERSION_SUNSET, Set.of());
        }

        Set<String> unknown = new LinkedHashSet<>(fields);
        unknown.removeAll(capabilityVersion.knownFields());
        if (!unknown.isEmpty()) {
            return VersionDecision.rejected(version, VersionRejectReason.UNKNOWN_FIELD, Set.copyOf(unknown));
        }

        List<String> warnings = new ArrayList<>();
        if (capabilityVersion.status() == VersionStatus.DEPRECATED) {
            warnings.add("版本 " + version + " 已弃用，请尽快迁移到新版本");
        }
        return VersionDecision.accepted(version, List.copyOf(warnings));
    }
}
