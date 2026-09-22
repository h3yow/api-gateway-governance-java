package com.github.h3yow.apigatewaygovernancejava.governance.downstream;

import com.github.h3yow.apigatewaygovernancejava.governance.retry.DownstreamErrorType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地可复现的"下游服务"模拟：无任何外部依赖、真实地址或凭据。
 *
 * <p>行为约定：
 * <ul>
 *   <li>{@code X-Failure-Scenario} 可注入 timeout / connection / business / unknown 四类故障，
 *       不指定（null/空/success）时正常返回；</li>
 *   <li>写操作按幂等键去重：同键只有第一次调用真正"生效"并自增生效计数，
 *       后续调用（含超时重试、重复请求）返回相同引用且 deduplicated=true；</li>
 *   <li>特别地，failureScenario=timeout 的首次写调用会在"生效之后"再抛超时，
 *       用于复现"下游已生效、响应丢失"的真实模糊场景，验证重试不会重复生效。</li>
 * </ul>
 */
@Service
public class SimulatedDownstreamService {

    private static final Set<String> KNOWN_SCENARIOS = Set.of("timeout", "connection", "business", "unknown");

    private final AtomicLong invocationSequence = new AtomicLong();
    private final AtomicLong writeEffectCount = new AtomicLong();
    /** 幂等键 -> 已生效结果引用，模拟下游侧的幂等台账。 */
    private final Map<String, String> processedKeys = new ConcurrentHashMap<>();
    /** 记录每个幂等键是否已"演过一次超时"，保证只在首次生效后超时一次。 */
    private final Set<String> timeoutPlayed = ConcurrentHashMap.newKeySet();

    public DownstreamResponse invoke(DownstreamRequest request) {
        invocationSequence.incrementAndGet();
        String scenario = request.failureScenario() == null
                ? "" : request.failureScenario().trim().toLowerCase();
        if (!scenario.isEmpty() && !KNOWN_SCENARIOS.contains(scenario)) {
            throw new DownstreamException(DownstreamErrorType.UNKNOWN,
                    "未知故障注入场景: " + scenario, null);
        }

        if (request.write()) {
            return invokeWrite(request, scenario);
        }
        throwScenarioIfNeeded(scenario, null);
        return new DownstreamResponse(true, "read-" + invocationSequence.get(),
                false, Map.of("route", request.routeKey(), "echo", safePayload(request)));
    }

    private DownstreamResponse invokeWrite(DownstreamRequest request, String scenario) {
        String key = request.idempotencyKey();

        // 连接失败模拟"请求未到达下游"：不发生任何生效，也不写台账，每次重试同样失败。
        if ("connection".equals(scenario)) {
            throwScenarioIfNeeded("connection", key);
        }

        // 进入处理前先探测：键已在台账中，说明这是重试/重复请求，结果应当重放。
        boolean replayed = processedKeys.containsKey(key);

        if ("timeout".equals(scenario) && !replayed && timeoutPlayed.add(key)) {
            // 关键模糊场景：下游已生效（台账已落、生效计数已增），但响应在网络上"超时丢失"。
            // 注意 computeIfAbsent 的映射函数对每个键只执行一次 => 真正"生效"只发生一次。
            processedKeys.computeIfAbsent(key, k -> {
                writeEffectCount.incrementAndGet();
                return request.routeKey() + "-ref-" + writeEffectCount.get();
            });
            throw new DownstreamException(DownstreamErrorType.TIMEOUT,
                    "simulated timeout after effect key=" + key, null);
        }

        // 正常路径（含超时后的重试）：computeIfAbsent 保证首次生效，后续直接取台账引用。
        String reference = processedKeys.computeIfAbsent(key, k -> {
            long seq = writeEffectCount.incrementAndGet();
            return request.routeKey() + "-ref-" + seq;
        });

        // 已生效键的再次进入（典型：首次"生效后超时"后的重试）：按幂等台账成功重放，
        // 不再重演故障——这正是写操作重试不重复生效且能收敛到成功的关键。
        if (replayed) {
            return buildWriteResponse(request, key, reference, true);
        }

        // 业务拒绝/未知错误在生效检查点之后模拟（业务系统读到请求后拒绝），结果确定不重试。
        throwScenarioIfNeeded(scenario, key);

        return buildWriteResponse(request, key, reference, false);
    }

    private DownstreamResponse buildWriteResponse(DownstreamRequest request, String key,
                                                  String reference, boolean replayed) {
        return new DownstreamResponse(true, reference, replayed,
                Map.of("route", request.routeKey(),
                        "idempotencyKey", key,
                        "deduplicated", replayed,
                        "payload", safePayload(request)));
    }

    private void throwScenarioIfNeeded(String scenario, String key) {
        switch (scenario) {
            case "timeout" -> throw new DownstreamException(DownstreamErrorType.TIMEOUT,
                    "simulated timeout key=" + key, null);
            case "connection" -> throw new DownstreamException(DownstreamErrorType.CONNECTION_FAILED,
                    "simulated connection refused key=" + key, null);
            case "business" -> throw new DownstreamException(DownstreamErrorType.BUSINESS_REJECTED,
                    "simulated business rule rejection key=" + key, null);
            case "unknown" -> throw new DownstreamException(DownstreamErrorType.UNKNOWN,
                    "simulated unmapped failure key=" + key,
                    new IllegalStateException("internal-detail-should-not-leak"));
            default -> {
            }
        }
    }

    private Map<String, Object> safePayload(DownstreamRequest request) {
        return new LinkedHashMap<>(request.payload() == null ? Map.of() : request.payload());
    }

    /** 下游被实际调用的总次数（含重试）。 */
    public long invocationCount() {
        return invocationSequence.get();
    }

    /** 写操作真正生效次数——重试与重复请求都不应使其超过去重后的键数。 */
    public long writeEffectCount() {
        return writeEffectCount.get();
    }

    /** 测试辅助：重置模拟状态。 */
    public void reset() {
        invocationSequence.set(0L);
        writeEffectCount.set(0L);
        processedKeys.clear();
        timeoutPlayed.clear();
    }
}
