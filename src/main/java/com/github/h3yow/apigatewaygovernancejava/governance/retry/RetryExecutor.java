package com.github.h3yow.apigatewaygovernancejava.governance.retry;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import com.github.h3yow.apigatewaygovernancejava.governance.downstream.DownstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 安全重试执行器。
 *
 * <p>重试纪律（确定且可解释）：
 * <ul>
 *   <li>仅 {@link DownstreamErrorType#TIMEOUT} 与 {@link DownstreamErrorType#CONNECTION_FAILED}
 *       这类"结果未知"的传输故障会重试；业务拒绝（结果确定）与未知错误一律不重试；</li>
 *   <li>写操作必须携带非空幂等键，否则拒绝执行（防止重复生效）；
 *       实际去重由下游按幂等键保证，因此即使超时发生在"下游已生效、响应丢失"之后，
 *       重试也只会返回同一结果而不会二次生效；</li>
 *   <li>退避固定为 backoffMillis（本地可复现、确定性）；</li>
 *   <li>重试耗尽后归一化为 {@link GovernanceException}，内部 cause 只写日志。</li>
 * </ul>
 */
@Component
public class RetryExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RetryExecutor.class);

    public RetryExecutor() {
    }

    /**
     * 执行一次受治理的下游调用。
     *
     * @param policy         重试策略（maxAttempts 含首次）
     * @param idempotencyKey 写操作必传；null/空表示只读，允许直接重试
     * @param action         下游调用动作（可能抛 {@link DownstreamException}）
     */
    public <T> T execute(RetryPolicy policy, String idempotencyKey,
                         Supplier<T> action) throws GovernanceException {
        return executeWithAttempts(policy, idempotencyKey, action).value();
    }

    /** 同 {@link #execute}，但额外返回实际尝试次数。 */
    public <T> RetryOutcome<T> executeWithAttempts(RetryPolicy policy, String idempotencyKey,
                                                   Supplier<T> action) throws GovernanceException {
        int maxAttempts = Math.max(1, policy.maxAttempts());
        DownstreamErrorType lastType = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return new RetryOutcome<>(action.get(), attempt);
            } catch (DownstreamException ex) {
                lastType = ex.type();
                LOG.warn("下游调用失败 attempt={}/{} type={} (内部细节仅入日志: {})",
                        attempt, maxAttempts, ex.type(), ex.getMessage());
                if (!ex.type().retryable() || attempt == maxAttempts) {
                    lastType = ex.type();
                    throw toGovernance(ex.type(), attempt, ex);
                }
                sleep(policy.backoffMillis());
            } catch (RuntimeException ex) {
                // 未归类异常：不重试，避免在副作用未知时放大影响；对外脱敏。
                LOG.error("下游抛出未归类异常，不重试 (内部细节仅入日志)", ex);
                throw new GovernanceException(ErrorCode.DOWNSTREAM_UNKNOWN_ERROR,
                        "下游处理发生内部错误，请求未被重试");
            }
        }
        throw toGovernance(lastType, maxAttempts, null);
    }

    /** 写操作的幂等键守卫，由编排层在入口调用。 */
    public void requireIdempotencyKeyForWrite(boolean write, String idempotencyKey) {
        if (write && (idempotencyKey == null || idempotencyKey.isBlank())) {
            throw new GovernanceException(ErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "写操作必须提供 X-Idempotency-Key 幂等键，否则不予执行以防重复生效");
        }
    }

    private GovernanceException toGovernance(DownstreamErrorType type, int attempts, Throwable cause) {
        ErrorCode code = switch (type) {
            case TIMEOUT -> ErrorCode.DOWNSTREAM_TIMEOUT;
            case CONNECTION_FAILED -> ErrorCode.DOWNSTREAM_CONNECTION_FAILED;
            case BUSINESS_REJECTED -> ErrorCode.DOWNSTREAM_BUSINESS_REJECTED;
            case UNKNOWN -> ErrorCode.DOWNSTREAM_UNKNOWN_ERROR;
        };
        String message = switch (type) {
            case TIMEOUT -> "下游处理超时，已按策略尝试 " + attempts + " 次（写操作经幂等去重，不会重复生效）";
            case CONNECTION_FAILED -> "下游连接失败，已按策略尝试 " + attempts + " 次";
            case BUSINESS_REJECTED -> "下游业务拒绝，结果确定，未进行重试";
            case UNKNOWN -> "下游发生未归类错误，未进行重试";
        };
        // 注意：不把 cause 传给 GovernanceException 的对外链路，避免细节透出；仅日志保留。
        LOG.debug("归一化错误 type={} code={} attempts={}", type, code, attempts, cause);
        return new GovernanceException(code, message);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
