package com.github.h3yow.apigatewaygovernancejava.governance.retry;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 基于 ConcurrentHashMap 的进程内幂等存储，保证同一幂等键在并发/重试下只生效一次。
 *
 * <p>每个键对应一个 {@link Entry}：
 * <ul>
 *   <li>首个进入者用 CAS 抢到执行权并真正运行 supplier；</li>
 *   <li>并发重复请求在 latch 上短暂等待：先到者完成则直接 {@code Replayed} 同一结果，
 *       绝不重复执行业务；等待超时仍在处理中则返回 {@code InFlight}（上层映射为 409）；</li>
 *   <li>执行失败会驱逐该键，允许稍后由调用方携带同一键重新发起（下游仍按幂等键去重）。</li>
 * </ul>
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    /** 并发重复请求等待首个请求完成的最长时间。 */
    private static final long WAIT_MILLIS = 2_000L;

    private static final class Entry {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Object value;
        private volatile boolean failed;
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> IdempotentOutcome<T> execute(String idempotencyKey, Supplier<T> supplier) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 无幂等键（只读等）直接执行，不做去重。
            return new IdempotentOutcome.Executed<>(supplier.get());
        }
        Entry created = new Entry();
        Entry existing = entries.putIfAbsent(idempotencyKey, created);
        if (existing == null) {
            // 首个进入者：真正执行。
            try {
                T result = supplier.get();
                created.value = result;
                created.done.countDown();
                return new IdempotentOutcome.Executed<>(result);
            } catch (RuntimeException ex) {
                created.failed = true;
                created.done.countDown();
                entries.remove(idempotencyKey, created);
                throw ex;
            }
        }

        // 重复请求：等待首个请求落定。
        boolean finished;
        try {
            finished = existing.done.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new IdempotentOutcome.InFlight<>(idempotencyKey);
        }
        if (finished && !existing.failed) {
            return new IdempotentOutcome.Replayed<>((T) existing.value);
        }
        // 仍在执行中或首请求已失败：交由上层给出 409/可重试结论，不重复执行。
        return new IdempotentOutcome.InFlight<>(idempotencyKey);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getCompleted(String idempotencyKey) {
        Entry entry = entries.get(idempotencyKey);
        if (entry == null || entry.failed || entry.done.getCount() > 0) {
            return Optional.empty();
        }
        return Optional.of((T) entry.value);
    }
}
