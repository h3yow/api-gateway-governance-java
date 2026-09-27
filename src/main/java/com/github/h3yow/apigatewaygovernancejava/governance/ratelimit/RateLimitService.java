package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
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
 * 任一环节拒绝即短路返回带明确原因的结论；全过才放行。
 *
 * <p>可观测与可执行反馈：
 * <ul>
 *   <li>每次结论携带两个维度在两种口径下的剩余额度（{@link QuotaReport}），
 *       放行后的余额反映本次实际占用；拒绝时被打满口径剩余必然不足 1；
 *       结论、余额与实际占用来自同一次原子 CAS，严格自洽；</li>
 *   <li>被拒绝时给出基于真实状态推算的 {@code retryAfterMillis}：
 *       窗口口径为到窗口滚动的剩余时长，令牌桶口径为按填充速率补出 1 枚令牌的时长；</li>
 *   <li>{@link #snapshot()} 提供只读的运行期配额快照，不占用、不改变任何计数。</li>
 * </ul>
 */
@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private volatile LongSupplier clock = System::currentTimeMillis;

    /**
     * 单个维度 key 的全部状态：窗口/桶限流器 + 本维度累计放行/拒绝计数。
     * 状态记录本身不可变替换；计数用独立原子量，快照在同一对象上读取，保证同维度一致。
     */
    private record ScopeState(FixedWindowQuotaLimiter window,
                              TokenBucketLimiter burst,
                              long windowLimit,
                              AtomicLong totalAllowed,
                              AtomicLong totalRejected) {
        long allowed() {
            return totalAllowed.get();
        }

        long rejected() {
            return totalRejected.get();
        }
    }

    /** 一个维度一次判定的完整结果。 */
    private record Eval(boolean allowed, RateLimitRejectReason reason, String detail,
                        ScopeQuota quota, long retryAfterMillis) {
        static Eval pass(ScopeQuota quota) {
            return new Eval(true, null, "allowed", quota, 0L);
        }

        static Eval reject(RateLimitRejectReason reason, String detail,
                           ScopeQuota quota, long retryAfterMillis) {
            return new Eval(false, reason, detail, quota, retryAfterMillis);
        }
    }

    private final ConcurrentMap<String, ScopeState> routeStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScopeState> callerStates = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitProperties properties) {
        this(properties, (LongSupplier) null);
    }

    /**
     * 可选注入一个时钟 Bean：生产环境没有该 Bean 时使用系统时钟；
     * 测试上下文可用可控时钟（如固定为 0）让窗口/回补时刻确定可断言。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitService(RateLimitProperties properties, ObjectProvider<LongSupplier> clockProvider) {
        this(properties, clockProvider.getIfAvailable());
    }

    private RateLimitService(RateLimitProperties properties, LongSupplier clockOverride) {
        this.properties = properties;
        if (clockOverride != null) {
            this.clock = clockOverride;
        }
    }

    /** 测试可注入固定/可控时钟。 */
    public void setClockForTests(LongSupplier testClock) {
        this.clock = testClock;
    }

    /**
     * 判定一次请求是否放行。结论始终携带两维度剩余额度；被拒绝时携带真实等待时间。
     */
    public RateLimitDecision check(String callerId, String routeKey) {
        long now = clock.getAsLong();

        ScopeState route = routeStates.computeIfAbsent(routeKey,
                k -> buildState(resolveRouteRule(k)));
        Eval routeEval = evaluate(route, now,
                RateLimitRejectReason.ROUTE_WINDOW_QUOTA,
                RateLimitRejectReason.ROUTE_BURST, "route=" + routeKey);

        if (!routeEval.allowed()) {
            // 路由维度短路：调用方维度完全未参与判定，仅做只读快照（不占其任何计数）。
            ScopeState caller = callerStates.computeIfAbsent(callerId,
                    k -> buildState(resolveCallerRule(k)));
            route.totalRejected().incrementAndGet();
            QuotaReport report = new QuotaReport(routeEval.quota(), peekQuota(caller, now));
            return RateLimitDecision.reject(routeEval.reason(), routeEval.detail(),
                    report, routeEval.retryAfterMillis());
        }
        route.totalAllowed().incrementAndGet();

        ScopeState caller = callerStates.computeIfAbsent(callerId,
                k -> buildState(resolveCallerRule(k)));
        Eval callerEval = evaluate(caller, now,
                RateLimitRejectReason.CALLER_WINDOW_QUOTA,
                RateLimitRejectReason.CALLER_BURST, "caller=" + callerId);

        if (!callerEval.allowed()) {
            caller.totalRejected().incrementAndGet();
            QuotaReport report = new QuotaReport(routeEval.quota(), callerEval.quota());
            return RateLimitDecision.reject(callerEval.reason(), callerEval.detail(),
                    report, callerEval.retryAfterMillis());
        }
        caller.totalAllowed().incrementAndGet();

        return RateLimitDecision.pass(new QuotaReport(routeEval.quota(), callerEval.quota()));
    }

    private Eval evaluate(ScopeState state, long now,
                          RateLimitRejectReason windowReason,
                          RateLimitRejectReason burstReason, String scope) {
        FixedWindowQuotaLimiter.WindowView windowView = null;
        TokenBucketLimiter.BucketView bucketView = null;

        if (state.window() != null) {
            FixedWindowQuotaLimiter.AcquireResult wr = state.window().acquire(now);
            windowView = new FixedWindowQuotaLimiter.WindowView(
                    state.windowLimit(), wr.used(), wr.remaining(),
                    wr.windowStartMillis(), wr.resetAtMillis());
            if (!wr.allowed()) {
                ScopeQuota quota = new ScopeQuota(windowView, peekBucketSnapshot(state, now));
                return Eval.reject(windowReason,
                        scope + " 固定窗口配额已用尽 (" + windowReason.description() + ")",
                        quota, wr.retryAfterMillis());
            }
        }
        if (state.burst() != null) {
            TokenBucketLimiter.AcquireResult br = state.burst().acquire(now);
            bucketView = new TokenBucketLimiter.BucketView(
                    br.capacity(), br.remaining(), br.used(), br.refillTokensPerSecond());
            if (!br.allowed()) {
                ScopeQuota quota = new ScopeQuota(windowView, bucketView);
                return Eval.reject(burstReason,
                        scope + " 令牌桶瞬时突发已耗尽 (" + burstReason.description() + ")",
                        quota, br.retryAfterMillis());
            }
        }
        return Eval.pass(new ScopeQuota(windowView, bucketView));
    }

    /** 只读查看某维度（不占用、不翻转、不落盘）。 */
    private ScopeQuota peekQuota(ScopeState state, long now) {
        return new ScopeQuota(peekWindowSnapshot(state, now), peekBucketSnapshot(state, now));
    }

    private FixedWindowQuotaLimiter.WindowView peekWindowSnapshot(ScopeState state, long now) {
        return state.window() == null ? null : state.window().peek(now);
    }

    private TokenBucketLimiter.BucketView peekBucketSnapshot(ScopeState state, long now) {
        return state.burst() == null ? null : state.burst().peek(now);
    }

    /**
     * 只读运行期快照：覆盖所有已懒加载的路由/调用方维度。
     * 不创建新限流器、不改变任何状态。
     */
    public RateLimitSnapshot snapshot() {
        long now = clock.getAsLong();
        return new RateLimitSnapshot(now, collect(routeStates, now), collect(callerStates, now));
    }

    private Map<String, ScopeSnapshot> collect(ConcurrentMap<String, ScopeState> source, long now) {
        Map<String, ScopeSnapshot> out = new LinkedHashMap<>();
        for (Map.Entry<String, ScopeState> e : source.entrySet()) {
            ScopeState s = e.getValue();
            // 计数与视图取自同一 ScopeState；视图只读，呈现某一确定时刻的一致状态。
            out.put(e.getKey(), new ScopeSnapshot(e.getKey(),
                    peekWindowSnapshot(s, now), peekBucketSnapshot(s, now),
                    s.allowed(), s.rejected()));
        }
        return Map.copyOf(out);
    }

    private ScopeState buildState(RateLimitRule rule) {
        FixedWindowQuotaLimiter window = rule.windowEnabled()
                ? new FixedWindowQuotaLimiter(rule.windowQuota(), rule.windowMillis()) : null;
        TokenBucketLimiter burst = rule.burstEnabled()
                ? new TokenBucketLimiter(rule.burstCapacity(), rule.refillPerSecond()) : null;
        return new ScopeState(window, burst, rule.windowEnabled() ? rule.windowQuota() : 0L,
                new AtomicLong(), new AtomicLong());
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
