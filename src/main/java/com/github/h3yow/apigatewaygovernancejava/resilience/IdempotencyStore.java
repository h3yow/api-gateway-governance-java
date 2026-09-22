package com.github.h3yow.apigatewaygovernancejava.resilience;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 写操作幂等存储：同一幂等键的并发重复调用折叠为一次实际执行，
 * 终态结果（成功或失败）被永久缓存，后续同键调用直接复用，
 * 保证写操作不会因重试或并发重放而重复生效。
 */
public class IdempotencyStore {

    private final ConcurrentMap<String, CompletableFuture<Object>> store = new ConcurrentHashMap<>();

    /**
     * 按幂等键执行：键不存在时执行 supplier 并缓存其终态结果；
     * 键已存在（无论执行中还是已终态）时直接等待并复用其结果。
     *
     * @throws DownstreamException 执行失败时抛出原始执行路径上的归一化异常
     */
    @SuppressWarnings("unchecked")
    public <T> T executeOnce(String idempotencyKey, Supplier<T> supplier) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(supplier, "supplier");

        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = store.putIfAbsent(idempotencyKey, mine);
        if (existing == null) {
            // 本线程赢得执行权，实际下游调用只发生一次
            try {
                mine.complete(supplier.get());
            } catch (Throwable t) {
                mine.completeExceptionally(t);
            }
            existing = mine;
        }
        // 失败结果同样被缓存：未知效果的写操作不允许第二次执行
        try {
            return (T) existing.join();
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error er) {
                throw er;
            }
            throw new DownstreamException(ErrorType.INTERNAL, "下游调用失败", cause);
        }
    }
}
