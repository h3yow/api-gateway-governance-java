package com.github.h3yow.apigatewaygovernancejava.governance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 重试配置（前缀 governance.retry）。 */
@ConfigurationProperties(prefix = "governance.retry")
public class RetryProperties {

    /** 最大尝试次数（含首次），仅对超时/连接失败生效。 */
    private int maxAttempts = 3;

    /** 固定退避毫秒数（本地确定性，不引入随机抖动依赖）。 */
    private long backoffMillis = 20L;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBackoffMillis() {
        return backoffMillis;
    }

    public void setBackoffMillis(long backoffMillis) {
        this.backoffMillis = backoffMillis;
    }
}
