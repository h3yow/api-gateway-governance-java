package com.github.h3yow.apigatewaygovernancejava.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 治理层配置项（前缀 governance）。
 */
@ConfigurationProperties(prefix = "governance")
public class GovernanceProperties {

    private final Window caller = new Window();
    private final Window route = new Window();
    private final Retry retry = new Retry();

    public Window getCaller() { return caller; }
    public Window getRoute() { return route; }
    public Retry getRetry() { return retry; }

    public static class Window {
        private long windowLimit = 10;
        private Duration window = Duration.ofSeconds(1);
        private long burstCapacity = 0;
        public long getWindowLimit() { return windowLimit; }
        public void setWindowLimit(long windowLimit) { this.windowLimit = windowLimit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
        public long getBurstCapacity() { return burstCapacity; }
        public void setBurstCapacity(long burstCapacity) { this.burstCapacity = burstCapacity; }
    }

    public static class Retry {
        private int readMaxAttempts = 3;
        private int writeMaxAttempts = 2;
        private long backoffMillis = 10;
        public int getReadMaxAttempts() { return readMaxAttempts; }
        public void setReadMaxAttempts(int v) { this.readMaxAttempts = v; }
        public int getWriteMaxAttempts() { return writeMaxAttempts; }
        public void setWriteMaxAttempts(int v) { this.writeMaxAttempts = v; }
        public long getBackoffMillis() { return backoffMillis; }
        public void setBackoffMillis(long v) { this.backoffMillis = v; }
    }
}
