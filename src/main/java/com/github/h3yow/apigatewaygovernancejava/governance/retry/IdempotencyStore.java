package com.github.h3yow.apigatewaygovernancejava.governance.retry;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写操作幂等存储：以幂等键去重，保证同一键在并发/重试下只生效一次。
 * 首次进入 IN_FLIGHT，完成后落为 COMPLETED 并缓存结果；重复调用复用结果，不重复执行。
 */
public interface IdempotencyStore {

    /**
     * 按幂等键执行：若已完成则直接返回缓存结果；若进行中则返回 pending 信号；
     * 仅首个调用方真正执行 supplier。
     */
    <T> IdempotentOutcome<T> execute(String idempotencyKey, Supplier<T> supplier);

    /** 查询是否已有完成结果。 */
    <T> Optional<T> getCompleted(String idempotencyKey);

    /** 一次幂等执行的结论。 */
    sealed interface IdempotentOutcome<T> {
        /** 本次调用真正执行并成功。 */
        record Executed<T>(T value) implements IdempotentOutcome<T> {
        }

        /** 命中此前已完成的结果（重复请求/重试回放）。 */
        record Replayed<T>(T value) implements IdempotentOutcome<T> {
        }

        /** 同一键已有调用在执行中。 */
        record InFlight<T>(String key) implements IdempotentOutcome<T> {
        }
    }
}
