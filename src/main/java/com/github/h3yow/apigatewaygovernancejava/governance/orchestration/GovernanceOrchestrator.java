package com.github.h3yow.apigatewaygovernancejava.governance.orchestration;

import com.github.h3yow.apigatewaygovernancejava.governance.common.ErrorCode;
import com.github.h3yow.apigatewaygovernancejava.governance.common.GovernanceException;
import com.github.h3yow.apigatewaygovernancejava.governance.config.RetryProperties;
import com.github.h3yow.apigatewaygovernancejava.governance.downstream.DownstreamRequest;
import com.github.h3yow.apigatewaygovernancejava.governance.downstream.DownstreamResponse;
import com.github.h3yow.apigatewaygovernancejava.governance.downstream.SimulatedDownstreamService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitRejectedException;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.IdempotencyStore;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.RetryExecutor;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.RetryOutcome;
import com.github.h3yow.apigatewaygovernancejava.governance.retry.RetryPolicy;
import com.github.h3yow.apigatewaygovernancejava.governance.version.PayloadValidator;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionResolution;
import com.github.h3yow.apigatewaygovernancejava.governance.version.VersionRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 治理编排，处理顺序确定、每步结论可解释：
 * <pre>
 *   限流(路由+调用方 / 窗口+突发) -> 版本解析(生命周期) -> 字段契约校验
 *   -> 写操作幂等守卫 -> 入口幂等去重 -> 安全重试调用下游
 * </pre>
 * 任一步失败都抛出带稳定 ErrorCode 的 {@link GovernanceException}。
 */
@Service
public class GovernanceOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(GovernanceOrchestrator.class);

    private final RateLimitService rateLimitService;
    private final VersionRoutingService versionRoutingService;
    private final PayloadValidator payloadValidator;
    private final RetryExecutor retryExecutor;
    private final IdempotencyStore idempotencyStore;
    private final SimulatedDownstreamService downstream;
    private final RetryProperties retryProperties;

    public GovernanceOrchestrator(RateLimitService rateLimitService,
                                  VersionRoutingService versionRoutingService,
                                  PayloadValidator payloadValidator,
                                  RetryExecutor retryExecutor,
                                  IdempotencyStore idempotencyStore,
                                  SimulatedDownstreamService downstream,
                                  RetryProperties retryProperties) {
        this.rateLimitService = rateLimitService;
        this.versionRoutingService = versionRoutingService;
        this.payloadValidator = payloadValidator;
        this.retryExecutor = retryExecutor;
        this.idempotencyStore = idempotencyStore;
        this.downstream = downstream;
        this.retryProperties = retryProperties;
    }

    public GovernanceResponse handle(GovernanceRequest request) {
        // 1) 限流：两维度两语义，拒绝原因可区分，结论携带剩余额度与等待时间。
        RateLimitDecision rate = rateLimitService.check(request.callerId(), request.routeKey());
        if (!rate.allowed()) {
            logDecision(request, rate, null, "REJECTED:" + rate.reason());
            throw new RateLimitRejectedException(rate);
        }

        // 2) 版本生命周期：未知/弃用/下线确定拒绝，不静默路由。
        VersionResolution version = versionRoutingService.resolve(request.capability(), request.version());

        // 3) 字段契约：删除/改名/未知/缺失均明确拒绝。
        GovernanceException payloadError = payloadValidator.validate(version.definition(), request.payload());
        if (payloadError != null) {
            logDecision(request, rate, version, "REJECTED:" + payloadError.errorCode());
            throw payloadError;
        }

        // 4) 写操作必须有幂等键（防止重试重复生效的第一道守卫）。
        retryExecutor.requireIdempotencyKeyForWrite(request.write(), request.idempotencyKey());

        // 5) 入口幂等去重：重复请求复用首个结果，不重复打到下游。
        if (request.write()) {
            IdempotencyStore.IdempotentOutcome<RetryOutcome<DownstreamResponse>> outcome =
                    idempotencyStore.execute(request.idempotencyKey(), () -> invokeDownstream(request));
            return switch (outcome) {
                case IdempotencyStore.IdempotentOutcome.Executed<RetryOutcome<DownstreamResponse>> e ->
                        respond(request, rate, version, e.value(), false);
                case IdempotencyStore.IdempotentOutcome.Replayed<RetryOutcome<DownstreamResponse>> r ->
                        respond(request, rate, version, r.value(), true);
                case IdempotencyStore.IdempotentOutcome.InFlight<RetryOutcome<DownstreamResponse>> i -> {
                    logDecision(request, rate, version, "REJECTED:IDEMPOTENCY_IN_FLIGHT");
                    throw new GovernanceException(ErrorCode.IDEMPOTENCY_IN_FLIGHT,
                            "相同幂等键的请求正在处理中，请勿并发重复提交；可稍后凭同一幂等键查询结果");
                }
            };
        }

        RetryOutcome<DownstreamResponse> outcome = invokeDownstream(request);
        return respond(request, rate, version, outcome, false);
    }

    /** 执行带重试的下游调用，并获得实际尝试次数（不污染对外业务 body）。 */
    private RetryOutcome<DownstreamResponse> invokeDownstream(GovernanceRequest request) {
        RetryPolicy policy = RetryPolicy.of(retryProperties.getMaxAttempts(),
                retryProperties.getBackoffMillis());
        return retryExecutor.executeWithAttempts(policy,
                request.write() ? request.idempotencyKey() : null,
                () -> downstream.invoke(toDownstream(request)));
    }

    private GovernanceResponse respond(GovernanceRequest request, RateLimitDecision rate,
                                       VersionResolution version,
                                       RetryOutcome<DownstreamResponse> outcome,
                                       boolean gatewayReplayed) {
        DownstreamResponse response = outcome.value();
        GovernanceResponse result = new GovernanceResponse(
                true, rate,
                version.definition().version().asString(),
                version.deprecated(),
                gatewayReplayed || response.deduplicated(),
                outcome.attempts(),
                response.body());
        logDecision(request, rate, version,
                "ACCEPTED ref=" + response.reference() + " dedup=" + result.deduplicated()
                        + " attempts=" + outcome.attempts());
        return result;
    }

    private DownstreamRequest toDownstream(GovernanceRequest request) {
        return new DownstreamRequest(request.routeKey(), request.idempotencyKey(),
                request.payload(), request.write(), request.failureScenario());
    }

    /** 请求输入 / 判定依据 / 最终结果三段式日志。 */
    private void logDecision(GovernanceRequest request, RateLimitDecision rate,
                             VersionResolution version, String result) {
        String input = "caller=" + request.callerId() + " route=" + request.routeKey()
                + " version=" + request.version() + " write=" + request.write()
                + " idempotencyKey=" + request.idempotencyKey();
        String basis = "rate=" + (rate.allowed() ? "ALLOW" : rate.reason())
                + " version=" + (version == null ? "n/a"
                : version.definition().version().asString() + "/" + version.definition().status());
        LOG.info("治理判定 | input: {} | basis: {} | result: {}", input, basis, result);
    }
}
