package com.github.h3yow.apigatewaygovernancejava.governance.config;

import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRule;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 限流配置（application.properties 前缀 governance.rate-limit）。
 * defaults 为全局默认规则；routes 按路由 key 覆盖；callers 按调用方 ID 覆盖。
 */
@ConfigurationProperties(prefix = "governance.rate-limit")
public class RateLimitProperties {

    private Rule defaults = new Rule();
    private Map<String, Rule> routes = Map.of();
    private Map<String, Rule> callers = Map.of();

    public Rule getDefaults() {
        return defaults;
    }

    public void setDefaults(Rule defaults) {
        this.defaults = defaults;
    }

    public Map<String, Rule> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Rule> routes) {
        this.routes = routes;
    }

    public Map<String, Rule> getCallers() {
        return callers;
    }

    public void setCallers(Map<String, Rule> callers) {
        this.callers = callers;
    }

    /** 可绑定的规则配置项。未显式配置的数值项为哨兵 -1，合并时回退到 defaults。 */
    public static class Rule {
        private long windowQuota = -1;
        private long windowMillis = -1;
        private long burstCapacity = -1;
        private double refillPerSecond = -1;

        public long getWindowQuota() {
            return windowQuota;
        }

        public void setWindowQuota(long windowQuota) {
            this.windowQuota = windowQuota;
        }

        public long getWindowMillis() {
            return windowMillis;
        }

        public void setWindowMillis(long windowMillis) {
            this.windowMillis = windowMillis;
        }

        public long getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(long burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        /**
         * 以 base 为底，用本规则中"已显式配置"的项覆盖。
         * 判定方式：数值项使用哨兵 -1 表示"未配置"，windowMillis 默认 60000 且始终可覆盖。
         */
        public RateLimitRule mergeOver(Rule base) {
            long windowQuota = this.windowQuota >= 0 ? this.windowQuota : base.windowQuota;
            long burstCapacity = this.burstCapacity >= 0 ? this.burstCapacity : base.burstCapacity;
            double refill = this.refillPerSecond >= 0 ? this.refillPerSecond : base.refillPerSecond;
            long windowMillis = this.windowMillis >= 0 ? this.windowMillis : base.windowMillis;
            return new RateLimitRule(windowQuota, windowMillis, burstCapacity, refill);
        }
    }
}
