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
        return acquire(nowMillis).allowed();
    }

    /**
     * 一次原子判定的完整结论：allowed 与"本次占用后的窗口状态"来自同一次 CAS，
     * 因此结论与计数严格自洽，不会出现"已用尽却放行"或"还有剩余却拒绝"。
     *
     * @param nowMillis 当前时刻（毫秒）
     * @return 不可变结果：是否放行、当前窗口已用/剩余、本窗口起点与重置时刻；
     *         被拒绝时 retryAfterMillis 为到下一窗口边界的真实等待（恒为正）
     */
    public AcquireResult acquire(long nowMillis) {
        long currentWindowStart = (nowMillis / windowMillis) * windowMillis;
        long resetAt = currentWindowStart + windowMillis;
        while (true) {
            Window snapshot = windowRef.get();
            if (snapshot.start() != currentWindowStart) {
                // 进入新窗口：首个发现者把快照原子翻转为 (新起点, 已用1)；
                // 翻转失败说明其他线程已推进，重读后继续，杜绝重复归零或丢计数。
                Window rolled = new Window(currentWindowStart, 1L);
                if (windowRef.compareAndSet(snapshot, rolled)) {
                    return new AcquireResult(true, 1L, quota - 1L, 0L,
                            currentWindowStart, resetAt);
                }
                Thread.onSpinWait();
                continue;
            }
            if (snapshot.count() >= quota) {
                // 拒绝时刻的恢复时间 = 当前固定窗口剩余时长，绝不使用固定常量。
                long waitMillis = Math.max(1L, resetAt - nowMillis);
                return new AcquireResult(false, quota, 0L, waitMillis,
                        currentWindowStart, resetAt);
            }
            // 同一窗口内：CAS 递增，每个配额只被一个请求拿走。
            long nextCount = snapshot.count() + 1;
            Window next = new Window(currentWindowStart, nextCount);
            if (windowRef.compareAndSet(snapshot, next)) {
                return new AcquireResult(true, nextCount, quota - nextCount, 0L,
                        currentWindowStart, resetAt);
            }
            Thread.onSpinWait();
        }
    }

    /**
     * 只读观测：读取当前窗口的一致快照，不发生任何 CAS / 翻转 / 计数变更。
     * 已滚动到历史窗口时按"新窗口尚未开始"归零呈现。
     */
    public WindowView peek(long nowMillis) {
        long currentWindowStart = (nowMillis / windowMillis) * windowMillis;
        long resetAt = currentWindowStart + windowMillis;
        Window snapshot = windowRef.get();
        long used = snapshot.start() == currentWindowStart ? snapshot.count() : 0L;
        long remaining = Math.max(0L, quota - used);
        return new WindowView(quota, used, remaining, currentWindowStart, resetAt);
    }

    /** 一次配额占用的完整结论（不可变）。拒绝时 retryAfterMillis 为正，放行时为 0。 */
    public record AcquireResult(boolean allowed, long used, long remaining,
                                long retryAfterMillis,
                                long windowStartMillis, long resetAtMillis) {
    }

    /** 固定窗口只读视图：硬上限、已用、剩余、窗口起点与重置时刻。 */
    public record WindowView(long limit, long used, long remaining,
                             long windowStartMillis, long resetAtMillis) {
    }

    /** 仅供观测/测试：当前窗口已用量（尚未开始或已滚动的窗口为 0）。 */
    public long currentCount(long nowMillis) {
        long currentWindowStart = (nowMillis / windowMillis) * windowMillis;
        Window snapshot = windowRef.get();
        return snapshot.start() == currentWindowStart ? snapshot.count() : 0L;
    }
}
