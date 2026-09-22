package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import com.github.h3yow.apigatewaygovernancejava.governance.config.RateLimitProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * 所有限流器按 key 懒加载并缓存在线程安全 Map 中，计数状态不会因并发丢失或错算。
 */
@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private LongSupplier clock = System::currentTimeMillis;

    private record LimiterPair(FixedWindowQuotaLimiter window, TokenBucketLimiter burst) {
    }

    private final ConcurrentMap<String, LimiterPair> routeLimiters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LimiterPair> callerLimiters = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitProperties properties) {
        this.properties = properties;
    }

    /** 测试可注入固定/可控时钟。 */
    void setClockForTests(LongSupplier testClock) {
        this.clock = testClock;
    }

    /**
     * 判定一次请求是否放行。被拒绝时返回的 decision 带有可区分原因与解释性细节。
     */
    public RateLimitDecision check(String callerId, String routeKey) {
        long now = clock.getAsLong();

        LimiterPair route = routeLimiters.computeIfAbsent(routeKey,
                k -> buildPair(resolveRouteRule(k)));
        RateLimitDecision routeDecision = checkPair(route, now,
                RateLimitRejectReason.ROUTE_WINDOW_QUOTA,
                RateLimitRejectReason.ROUTE_BURST, "route=" + routeKey);
        if (!routeDecision.allowed()) {
            return routeDecision;
        }

        LimiterPair caller = callerLimiters.computeIfAbsent(callerId,
                k -> buildPair(resolveCallerRule(k)));
        RateLimitDecision callerDecision = checkPair(caller, now,
                RateLimitRejectReason.CALLER_WINDOW_QUOTA,
                RateLimitRejectReason.CALLER_BURST, "caller=" + callerId);
        if (!callerDecision.allowed()) {
            return callerDecision;
        }

        return RateLimitDecision.pass();
    }

    private RateLimitDecision checkPair(LimiterPair pair, long now,
                                        RateLimitRejectReason windowReason,
                                        RateLimitRejectReason burstReason,
                                        String scope) {
        if (pair.window() != null && !pair.window().tryAcquire(now)) {
            return RateLimitDecision.reject(windowReason,
                    scope + " 固定窗口配额已用尽 (" + windowReason.description() + ")");
        }
        if (pair.burst() != null && !pair.burst().tryAcquire(now)) {
            return RateLimitDecision.reject(burstReason,
                    scope + " 令牌桶瞬时突发已耗尽 (" + burstReason.description() + ")");
        }
        return RateLimitDecision.pass();
    }

    private LimiterPair buildPair(RateLimitRule rule) {
        FixedWindowQuotaLimiter window =
                rule.windowEnabled() ? new FixedWindowQuotaLimiter(rule.windowQuota(), rule.windowMillis()) : null;
        TokenBucketLimiter burst =
                rule.burstEnabled() ? new TokenBucketLimiter(rule.burstCapacity(), rule.refillPerSecond()) : null;
        return new LimiterPair(window, burst);
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
