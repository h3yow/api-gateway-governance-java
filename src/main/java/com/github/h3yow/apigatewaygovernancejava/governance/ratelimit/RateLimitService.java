package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 限流入口：对一次请求按「服务路由」与「调用方」两个维度、每个维度先固定窗口配额
 * （硬上限）后令牌桶突发（稳态+突发）的顺序进行判定。
 *
 * <p>拒绝判定顺序是确定性的，便于解释：
 * <pre>
 *   1. 路由窗口配额  2. 路由令牌桶突发
 *   3. 调用方窗口配额 4. 调用方令牌桶突发
 * </pre>
 * 任一环节拒绝即短路返回带明确原因的结论；全过才放行。短路只影响“实际占用”：
 * 被短路的维度/口径不扣减任何额度，但其同一时刻的真实余量仍以<b>只读投影</b>带回。
 *
 * <p>每次判定的 {@link RateLimitDecision} 都同时携带两个维度在两种口径下的剩余额度
 * 与恢复等待时间：实际判定的口径数值与占用同源（取自同一次 CAS 结论），被短路的
 * 口径/维度取自同一 {@code now} 时刻的纯读投影（view，不做 CAS、不推进窗口/回补落盘、
 * 不占额度、不改计数、不影响拒绝原因与顶层等待时间）。并发下确定一致。
 * 限流器按 key 懒加载并缓存在线程安全 Map 中，计数状态不会因并发丢失或错算；
 * 只读投影不会为“从未出现过”的 key 创建缓存条目。
 */
@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private LongSupplier clock = System::currentTimeMillis;

    /** 单个维度 key 下的两个限流器与该维度的累计观测计数。 */
    private static final class ScopeState {
        final FixedWindowQuotaLimiter window;
        final TokenBucketLimiter burst;
        final LongAdder allowedTotal = new LongAdder();
        final LongAdder rejectedTotal = new LongAdder();

        ScopeState(FixedWindowQuotaLimiter window, TokenBucketLimiter burst) {
            this.window = window;
            this.burst = burst;
        }
    }

    private final ConcurrentMap<String, ScopeState> routeStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScopeState> callerStates = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** 测试可注入固定/可控时钟。 */
    public void setClockForTests(LongSupplier testClock) {
        this.clock = testClock;
    }

    /** 当前时钟（快照也用同一时刻，保证每条快照内部字段同源）。 */
    long currentTime() {
        return clock.getAsLong();
    }

    /**
     * 读取当前所有已懒加载维度（路由 / 调用方）的配额快照。纯读操作：
     * 不占用额度、不推进窗口、不做任何 CAS，不改变任何计数。
     *
     * <p>每个维度条目的字段取自同一 {@code now} 时刻、限流器内部单个不可变快照的投影，
     * 因此条目内部一致（不会半更新），且 used 恒 ≤ quota、remaining 恒非负。
     * 不同条目之间不构成跨维度的全局同一瞬间视图（读取期间计数可继续推进），
     * 这与“查询不阻塞流量、不改变状态”的取舍一致。
     */
    public RateLimitSnapshot snapshot() {
        long now = clock.getAsLong();
        Map<String, RateLimitSnapshot.DimensionSnapshot> routes = new LinkedHashMap<>();
        for (Map.Entry<String, ScopeState> e : routeStates.entrySet()) {
            routes.put(e.getKey(), snapshotScope(e.getValue(), now));
        }
        Map<String, RateLimitSnapshot.DimensionSnapshot> callers = new LinkedHashMap<>();
        for (Map.Entry<String, ScopeState> e : callerStates.entrySet()) {
            callers.put(e.getKey(), snapshotScope(e.getValue(), now));
        }
        return new RateLimitSnapshot(now, Map.copyOf(routes), Map.copyOf(callers));
    }

    private RateLimitSnapshot.DimensionSnapshot snapshotScope(ScopeState state, long now) {
        RateLimitSnapshot.WindowSnapshot window = null;
        if (state.window != null) {
            FixedWindowQuotaLimiter.WindowView v = state.window.view(now);
            window = new RateLimitSnapshot.WindowSnapshot(v.windowStart(), v.windowEnd(),
                    v.quota(), v.used(), v.remaining());
        }
        RateLimitSnapshot.BurstSnapshot burst = null;
        if (state.burst != null) {
            TokenBucketLimiter.BurstView v = state.burst.view(now);
            burst = new RateLimitSnapshot.BurstSnapshot(v.capacity(), v.refillPerSecond(),
                    v.availableTokens(), v.usedTokens());
        }
        return new RateLimitSnapshot.DimensionSnapshot(window, burst,
                state.allowedTotal.sum(), state.rejectedTotal.sum());
    }

    /**
     * 判定一次请求是否放行。无论放行还是被拒，decision 都同时携带两个维度、两种口径的
     * 剩余额度；被拒绝时还带可区分原因、解释性细节，以及基于被拒口径真实状态推算的
     * 顶层重试等待时间。被短路（未实际判定）的维度/口径只做只读投影，不占额度、不改计数。
     */
    public RateLimitDecision check(String callerId, String routeKey) {
        long now = clock.getAsLong();

        ScopeState route = routeStates.computeIfAbsent(routeKey,
                k -> buildState(resolveRouteRule(k)));
        ScopeCheck routeCheck = checkScope(route, now,
                RateLimitRejectReason.ROUTE_WINDOW_QUOTA,
                RateLimitRejectReason.ROUTE_BURST, "route=" + routeKey);
        if (!routeCheck.allowed()) {
            // 路由先挡住：调用方维度本次不判定、不占额度，只按同一 now 的真实状态只读展示。
            ScopeState caller = callerStates.get(callerId);
            if (caller == null) {
                // 从未出现过的调用方：按其规则构造一次性投影状态，不写入 callerStates
                // （读展示不产生“该调用方出现过”的副作用）。
                caller = buildState(resolveCallerRule(callerId));
            }
            DimensionQuotaView callerView = readOnlyQuota(caller, now);
            return RateLimitDecision.reject(routeCheck.reason(), routeCheck.detail(),
                    routeCheck.quota(), callerView.quota(), routeCheck.retryAfterMillis());
        }

        ScopeState caller = callerStates.computeIfAbsent(callerId,
                k -> buildState(resolveCallerRule(k)));
        ScopeCheck callerCheck = checkScope(caller, now,
                RateLimitRejectReason.CALLER_WINDOW_QUOTA,
                RateLimitRejectReason.CALLER_BURST, "caller=" + callerId);
        if (!callerCheck.allowed()) {
            // 路由维度本次已通过并实际占用，其剩余额度必须一并带回。
            return RateLimitDecision.reject(callerCheck.reason(), callerCheck.detail(),
                    routeCheck.quota(), callerCheck.quota(), callerCheck.retryAfterMillis());
        }

        return RateLimitDecision.pass(routeCheck.quota(), callerCheck.quota());
    }

    private ScopeCheck checkScope(ScopeState state, long now,
                                  RateLimitRejectReason windowReason,
                                  RateLimitRejectReason burstReason, String scope) {
        if (state.window != null) {
            FixedWindowQuotaLimiter.AcquireResult w = state.window.acquire(now);
            if (!w.allowed()) {
                state.rejectedTotal.increment();
                // 窗口口径拒绝：突发口径本次未判定，按同一时刻真实状态只读补齐，不抽令牌。
                RateLimitDecision.DimensionQuota quota =
                        mergeAcquiredWindowWithReadOnlyBurst(w, state.burst, now);
                return new ScopeCheck(false, windowReason,
                        scope + " 固定窗口配额已用尽 (" + windowReason.description()
                                + ")，预计 " + Math.max(1L, w.retryAfterMillis() / 1000L)
                                + " 秒后窗口滚动恢复",
                        quota, w.retryAfterMillis());
            }
            if (state.burst != null) {
                TokenBucketLimiter.AcquireResult b = state.burst.acquire(now);
                if (!b.allowed()) {
                    state.rejectedTotal.increment();
                    // 窗口本次已真实占用（remaining 为扣减后余量）；突发被拒 remaining 恒 < 1。
                    RateLimitDecision.DimensionQuota quota = RateLimitDecision.DimensionQuota.of(
                            w.remaining(), 0L, b.remaining(), b.retryAfterMillis());
                    return new ScopeCheck(false, burstReason,
                            scope + " 令牌桶瞬时突发已耗尽 (" + burstReason.description()
                                    + ")，按回补速率预计 "
                                    + Math.max(1L, b.retryAfterMillis() / 1000L) + " 秒后可重试",
                            quota, b.retryAfterMillis());
                }
                state.allowedTotal.increment();
                return new ScopeCheck(true, null, null,
                        RateLimitDecision.DimensionQuota.of(
                                w.remaining(), 0L, b.remaining(), 0L), 0L);
            }
            state.allowedTotal.increment();
            return new ScopeCheck(true, null, null,
                    RateLimitDecision.DimensionQuota.ofWindow(w.remaining(), 0L), 0L);
        }
        if (state.burst != null) {
            TokenBucketLimiter.AcquireResult b = state.burst.acquire(now);
            if (!b.allowed()) {
                state.rejectedTotal.increment();
                return new ScopeCheck(false, burstReason,
                        scope + " 令牌桶瞬时突发已耗尽 (" + burstReason.description()
                                + ")，按回补速率预计 "
                                + Math.max(1L, b.retryAfterMillis() / 1000L) + " 秒后可重试",
                        RateLimitDecision.DimensionQuota.ofBurst(
                                b.remaining(), b.retryAfterMillis()),
                        b.retryAfterMillis());
            }
            state.allowedTotal.increment();
            return new ScopeCheck(true, null, null,
                    RateLimitDecision.DimensionQuota.ofBurst(b.remaining(), 0L), 0L);
        }
        state.allowedTotal.increment();
        return new ScopeCheck(true, null, null, null, 0L);
    }

    /**
     * 窗口口径被拒时，用同维度突发限流器的只读视图补齐突发余量：不调用 acquire、
     * 不做任何 CAS、不占用令牌、不改变计数；等待时间按当时真实桶状态推算，仅供展示。
     */
    private RateLimitDecision.DimensionQuota mergeAcquiredWindowWithReadOnlyBurst(
            FixedWindowQuotaLimiter.AcquireResult w, TokenBucketLimiter burst, long now) {
        if (burst == null) {
            return RateLimitDecision.DimensionQuota.ofWindow(
                    w.remaining(), w.retryAfterMillis());
        }
        DimensionQuotaView view = readOnlyBurst(burst, now);
        return RateLimitDecision.DimensionQuota.of(
                w.remaining(), w.retryAfterMillis(),
                view.burstRemaining(), view.burstRetryAfterMillis());
    }

    /**
     * 只读投影：在 {@code now} 时刻读取整个维度两个口径的真实余量，不做 CAS、
     * 不推进窗口、不落盘回补进度、不占额度、不改任何计数（含维度累计观测计数）。
     */
    private DimensionQuotaView readOnlyQuota(ScopeState state, long now) {
        Long windowRemaining = null;
        Long windowWait = null;
        if (state.window != null) {
            FixedWindowQuotaLimiter.WindowView v = state.window.view(now);
            windowRemaining = v.remaining();
            // 只读维度若恰好也处于耗尽态（used 达硬上限），给出其自身真实恢复等待；
            // 有余量则等待为 0。不影响顶层 retryAfterMillis（顶层只取实际被拒口径的等待）。
            windowWait = v.used() >= v.quota()
                    ? Math.max(0L, v.windowEnd() - now) : 0L;
        }
        Double burstRemaining = null;
        Long burstWait = null;
        if (state.burst != null) {
            DimensionQuotaView b = readOnlyBurst(state.burst, now);
            burstRemaining = b.burstRemaining();
            burstWait = b.burstRetryAfterMillis();
        }
        return new DimensionQuotaView(windowRemaining, windowWait, burstRemaining, burstWait);
    }

    /** 令牌桶只读投影：余量与“回补到 1 枚令牌”的等待均按当前真实桶状态计算，不抽令牌。 */
    private DimensionQuotaView readOnlyBurst(TokenBucketLimiter burst, long now) {
        TokenBucketLimiter.BurstView v = burst.view(now);
        double available = v.availableTokens();
        long wait = available < 1.0d
                ? Math.max(1L, (long) Math.ceil((1.0d - available) * 1000.0d
                        / v.refillPerSecond()))
                : 0L;
        return new DimensionQuotaView(null, null, available, wait);
    }

    /** 只读投影的内部载体；{@link #quota()} 按启用口径组装为对外 {@code DimensionQuota}。 */
    private record DimensionQuotaView(Long windowRemaining, Long windowRetryAfterMillis,
                                      Double burstRemaining, Long burstRetryAfterMillis) {
        RateLimitDecision.DimensionQuota quota() {
            if (windowRemaining != null && burstRemaining != null) {
                return RateLimitDecision.DimensionQuota.of(windowRemaining, windowRetryAfterMillis,
                        burstRemaining, burstRetryAfterMillis);
            }
            if (windowRemaining != null) {
                return RateLimitDecision.DimensionQuota.ofWindow(
                        windowRemaining, windowRetryAfterMillis);
            }
            if (burstRemaining != null) {
                return RateLimitDecision.DimensionQuota.ofBurst(
                        burstRemaining, burstRetryAfterMillis);
            }
            return null;
        }
    }

    /** 维度内部判定结果；reason/detail 在组装对外结论时补齐。 */
    private record ScopeCheck(boolean allowed, RateLimitRejectReason reason, String detail,
                              RateLimitDecision.DimensionQuota quota, long retryAfterMillis) {
    }

    private ScopeState buildState(RateLimitRule rule) {
        FixedWindowQuotaLimiter window =
                rule.windowEnabled() ? new FixedWindowQuotaLimiter(rule.windowQuota(), rule.windowMillis()) : null;
        TokenBucketLimiter burst =
                rule.burstEnabled() ? new TokenBucketLimiter(rule.burstCapacity(), rule.refillPerSecond()) : null;
        return new ScopeState(window, burst);
    }

    private RateLimitRule resolveRouteRule(String routeKey) {
        RateLimitProperties.Rule override = properties.getRoutes().get(routeKey);
        return override != null ? override.mergeOver(properties.getDefaults())
                : properties.getDefaults().mergeOver(properties.getDefaults());
    }

    private RateLimitRule resolveCallerRule(String callerId) {
        RateLimitProperties.Rule override = properties.getCallers().get(callerId);
        return override != null ? override.mergeOver(properties.getDefaults())
                : properties.getDefaults().mergeOver(properties.getDefaults());
    }
}
