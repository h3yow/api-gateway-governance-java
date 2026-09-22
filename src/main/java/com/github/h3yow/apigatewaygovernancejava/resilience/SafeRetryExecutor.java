package com.github.h3yow.apigatewaygovernancejava.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 安全重试执行器。
 *
 * <p>失败归一化：超时、连接失败、业务拒绝、内部错误映射为可区分的
 * {@link ErrorType}；原始异常仅进入服务端日志，不向调用方透出。</p>
 *
 * <p>重试边界：</p>
 * <ul>
 *   <li>读操作天然幂等，可在读策略允许的错误类型上重试（默认：超时、连接失败）。</li>
 *   <li>写操作仅对"连接失败"（请求确定未送达）重试；超时意味着效果未知，绝不重试。
 *       重试发生在幂等键的唯一执行权内，同键并发调用折叠为一次实际执行，
 *       终态结果（含失败）缓存复用，保证不重复生效。</li>
 * </ul>
 */
public class SafeRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(SafeRetryExecutor.class);

    private final RetryPolicy readPolicy;
    private final RetryPolicy writePolicy;
    private final IdempotencyStore idempotencyStore;

    public SafeRetryExecutor(RetryPolicy readPolicy, RetryPolicy writePolicy,
                             IdempotencyStore idempotencyStore) {
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy");
        this.writePolicy = Objects.requireNonNull(writePolicy, "writePolicy");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
    }

    /** 执行读操作（幂等，可按读策略重试）。 */
    public <T> RetryOutcome<T> executeRead(DownstreamCall<T> call) {
        Objects.requireNonNull(call, "call");
        return doAttempts(call, readPolicy, "(read)");
    }

    /**
     * 执行写操作（非幂等）。相同幂等键的并发/重复调用折叠为一次执行；
     * 仅连接失败会在唯一执行权内重试，超时不重试。
     */
    public <T> RetryOutcome<T> executeWrite(DownstreamCall<T> call, String idempotencyKey) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return idempotencyStore.executeOnce(idempotencyKey,
                () -> doAttempts(call, writePolicy, idempotencyKey));
    }

    private <T> RetryOutcome<T> doAttempts(DownstreamCall<T> call, RetryPolicy policy, String label) {
        Set<ErrorType> retryable = policy.retryReadOn();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                T value = call.call();
                log.info("调用成功 label={} attempts={}", label, attempt);
                return RetryOutcome.success(value, attempt);
            } catch (Exception raw) {
                DownstreamException de = normalize(raw);
                if (retryable.contains(de.getErrorType()) && attempt < policy.maxAttempts()) {
                    log.info("调用失败 label={} attempt={} type={}，{}ms 后重试（{}/{}）",
                            label, attempt, de.getErrorType(), policy.backoffMillis(),
                            attempt + 1, policy.maxAttempts());
                    if (!sleepBackoff(policy)) {
                        return RetryOutcome.failure(ErrorType.INTERNAL, "调用被中断", attempt);
                    }
                    continue;
                }
                log.info("调用终态失败 label={} attempts={} type={}", label, attempt, de.getErrorType());
                return RetryOutcome.failure(de.getErrorType(), safeMessage(de), attempt);
            }
        }
    }

    private boolean sleepBackoff(RetryPolicy policy) {
        try {
            Thread.sleep(policy.backoffMillis());
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String safeMessage(DownstreamException de) {
        // 只暴露归一化类型对应的固定文案，不透出 cause 细节
        return switch (de.getErrorType()) {
            case TIMEOUT -> "下游服务响应超时，请稍后重试或查询操作结果";
            case CONNECTION_FAILURE -> "下游服务暂时不可用，请稍后重试";
            case BUSINESS_REJECTION -> de.getMessage() != null ? de.getMessage() : "下游业务拒绝";
            case INTERNAL -> "服务内部错误，请联系管理员";
        };
    }

    /**
     * 将原始异常归一化为可区分的 {@link ErrorType}，原始细节仅保留在 cause 中供日志使用。
     */
    static DownstreamException normalize(Throwable t) {
        if (t instanceof BusinessRejectionException bre) {
            return bre;
        }
        if (t instanceof DownstreamException de) {
            return de;
        }
        if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
            return new DownstreamException(ErrorType.TIMEOUT, "下游响应超时", t);
        }
        if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
            return new DownstreamException(ErrorType.CONNECTION_FAILURE, "下游连接失败", t);
        }
        return new DownstreamException(ErrorType.INTERNAL, "下游调用发生未分类异常", t);
    }
}
