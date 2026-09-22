package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 固定窗口配额限流器：以 [windowStart, windowStart+windowMillis) 为窗口，
 * 窗口内计数达到配额即拒绝；窗口滚动后计数归零重新累计。
 *
 * <p>语义说明：
 * <ul>
 *   <li>窗口边界为 {@code nowMillis / windowMillis * windowMillis}，对所有线程一致；</li>
 *   <li>{@code (windowStart, count)} 作为同一个不可变快照用单个 AtomicReference + CAS 更新，
 *       "跨窗口归零"与"窗口内计数"不会被并发撕裂，因此并发下绝不超额放行也不误拒；</li>
 *   <li>该算法不做滑动窗口平滑，相邻窗口临界点附近最多出现 2x 瞬时流量，
 *       这属于固定窗口语义的明确取舍；稳态突发由 {@link TokenBucketLimiter} 约束。</li>
 * </ul>
 */
public final class FixedWindowQuotaLimiter {

    private final long quota;
    private final long windowMillis;

    /** 当前窗口起点及其计数的原子快照。 */
    private record Window(long start, long count) {
    }

    private final AtomicReference<Window> windowRef = new AtomicReference<>(new Window(-1L, 0L));

    public FixedWindowQuotaLimiter(long quota, long windowMillis) {
        if (quota <= 0) {
            throw new IllegalArgumentException("quota must be positive: " + quota);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive: " + windowMillis);
        }
        this.quota = quota;
        this.windowMillis = windowMillis;
    }

    /**
     * 尝试占用一个配额。
     *
     * @return true 表示放行；false 表示当前窗口配额已用尽
     */
    public boolean tryAcquire(long nowMillis) {
        long currentWindowStart = (nowMillis / windowMillis) * windowMillis;
        while (true) {
            Window snapshot = windowRef.get();
            if (snapshot.start() != currentWindowStart) {
                // 进入新窗口：首个发现者把快照原子翻转为 (新起点, 已用1)；
                // 翻转失败说明其他线程已推进，重读后继续，杜绝重复归零或丢计数。
                Window rolled = new Window(currentWindowStart, 1L);
                if (windowRef.compareAndSet(snapshot, rolled)) {
                    return true;
                }
                Thread.onSpinWait();
                continue;
            }
            if (snapshot.count() >= quota) {
                return false;
            }
            // 同一窗口内：CAS 递增，每个配额只被一个请求拿走。
            Window next = new Window(currentWindowStart, snapshot.count() + 1);
            if (windowRef.compareAndSet(snapshot, next)) {
                return true;
            }
            Thread.onSpinWait();
        }
    }

    /** 仅供观测/测试：当前窗口已用量（尚未开始或已滚动的窗口为 0）。 */
    public long currentCount(long nowMillis) {
        long currentWindowStart = (nowMillis / windowMillis) * windowMillis;
        Window snapshot = windowRef.get();
        return snapshot.start() == currentWindowStart ? snapshot.count() : 0L;
    }
}
