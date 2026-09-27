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
     * 尝试占用一个配额，并原子地带回本次占用之后的可观测结论。
     *
     * <p>结论与实际计数严格同源（都取自 CAS 成功/失败时的同一个不可变快照），
     * 因此不会出现“剩余 5 却被拒”或“已用尽却放行”的矛盾：
     * <ul>
     *   <li>放行：{@code remaining = quota - 占用后已用}，恒为非负；</li>
     *   <li>拒绝：{@code remaining = 0}，{@code retryAfterMillis} 为距离当前窗口结束的
     *       真实毫秒数（窗口滚动后才会有新配额），基于当时窗口状态推算，不是固定常量；</li>
     *   <li>被拒绝的请求不改变计数。</li>
     * </ul>
     */
    public AcquireResult acquire(long nowMillis) {
        long currentWindowStart = windowStart(nowMillis);
        while (true) {
            Window snapshot = windowRef.get();
            if (snapshot.start() != currentWindowStart) {
                // 进入新窗口：首个发现者把快照原子翻转为 (新起点, 已用1)；
                // 翻转失败说明其他线程已推进，重读后继续，杜绝重复归零或丢计数。
                Window rolled = new Window(currentWindowStart, 1L);
                if (windowRef.compareAndSet(snapshot, rolled)) {
                    return new AcquireResult(true, quota - 1L, 0L);
                }
                Thread.onSpinWait();
                continue;
            }
            if (snapshot.count() >= quota) {
                // 拒绝结论与“当前窗口”同源：等待时间即到窗口结束的剩余时间。
                long waitMillis = Math.max(0L, currentWindowStart + windowMillis - nowMillis);
                return new AcquireResult(false, 0L, waitMillis);
            }
            // 同一窗口内：CAS 递增，每个配额只被一个请求拿走。
            Window next = new Window(currentWindowStart, snapshot.count() + 1);
            if (windowRef.compareAndSet(snapshot, next)) {
                long remaining = quota - next.count();
                return new AcquireResult(true, remaining, 0L);
            }
            Thread.onSpinWait();
        }
    }

    /**
     * 只读快照：当前窗口起点/结束点、窗口硬上限、已用量与剩余量。
     * 不做任何 CAS、不改变计数；当内部窗口已滚动到新窗口时按新窗口（已用 0）呈现。
     */
    public WindowView view(long nowMillis) {
        long currentWindowStart = windowStart(nowMillis);
        Window snapshot = windowRef.get();
        long used = snapshot.start() == currentWindowStart ? snapshot.count() : 0L;
        long remaining = Math.max(0L, quota - used);
        return new WindowView(currentWindowStart, currentWindowStart + windowMillis,
                quota, used, remaining);
    }

    private long windowStart(long nowMillis) {
        return (nowMillis / windowMillis) * windowMillis;
    }

    /** 仅供观测/测试：当前窗口已用量（尚未开始或已滚动的窗口为 0）。 */
    public long currentCount(long nowMillis) {
        return view(nowMillis).used();
    }

    public long quota() {
        return quota;
    }

    public long windowMillis() {
        return windowMillis;
    }

    /** 单次占用的原子结论。 */
    public record AcquireResult(boolean allowed, long remaining, long retryAfterMillis) {
    }

    /** 固定窗口配额的只读快照（不可变）。 */
    public record WindowView(long windowStart, long windowEnd, long quota,
                             long used, long remaining) {
    }
}
