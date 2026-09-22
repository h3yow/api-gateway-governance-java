package com.github.h3yow.apigatewaygovernancejava.governance;

import com.github.h3yow.apigatewaygovernancejava.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewaygovernancejava.ratelimit.RateLimiter;
import com.github.h3yow.apigatewaygovernancejava.ratelimit.RejectReason;
import com.github.h3yow.apigatewaygovernancejava.resilience.BusinessRejectionException;
import com.github.h3yow.apigatewaygovernancejava.resilience.DownstreamCall;
import com.github.h3yow.apigatewaygovernancejava.resilience.ErrorType;
import com.github.h3yow.apigatewaygovernancejava.resilience.RetryOutcome;
import com.github.h3yow.apigatewaygovernancejava.resilience.SafeRetryExecutor;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionDecision;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionRejectReason;
import com.github.h3yow.apigatewaygovernancejava.versioning.VersionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 治理演示入口：限流 -> 版本/字段校验 -> 安全重试调用。
 * 任一环节拒绝都返回可区分原因；内部异常细节不透出。
 */
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private static final Logger log = LoggerFactory.getLogger(GovernanceController.class);

    private final RateLimiter rateLimiter;
    private final VersionRegistry versionRegistry;
    private final SafeRetryExecutor safeRetryExecutor;

    public GovernanceController(RateLimiter rateLimiter, VersionRegistry versionRegistry,
                                SafeRetryExecutor safeRetryExecutor) {
        this.rateLimiter = rateLimiter;
        this.versionRegistry = versionRegistry;
        this.safeRetryExecutor = safeRetryExecutor;
    }

    @PostMapping("/invoke")
    public ResponseEntity<?> invoke(@RequestHeader("X-Caller-Id") String callerId,
                                    @RequestHeader(value = "X-Api-Version", required = false) String version,
                                    @RequestBody InvokeRequest request) {
        String routeId = Objects.requireNonNullElse(request.routeId(), "orders");
        Map<String, Object> fields = Objects.requireNonNullElseGet(request.fields(), Map::of);
        log.info("收到请求 caller={} route={} version={} operation={} idempotencyKey={} fields={} simulate={}",
                callerId, routeId, version, request.operation(), request.idempotencyKey(),
                fields.keySet(), request.simulate());

        // 1) 限流：调用方 + 路由两维度
        RateLimitDecision rateDecision = rateLimiter.check(callerId, routeId);
        log.info("限流判定 caller={} route={} allowed={} reason={} remaining={}",
                callerId, routeId, rateDecision.allowed(), rateDecision.reason(),
                rateDecision.remainingTokens());
        if (!rateDecision.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("RATE_LIMITED:" + rateDecision.reason().name(),
                            "请求被限流：" + describe(rateDecision.reason()),
                            rateDecision.retryAfterMillis()));
        }

        // 2) 版本与字段兼容校验
        VersionDecision versionDecision = versionRegistry.validate(
                Objects.requireNonNullElse(version, ""), fields.keySet());
        log.info("版本判定 version={} accepted={} reason={} unknownFields={} warnings={}",
                versionDecision.version(), versionDecision.accepted(),
                versionDecision.rejectReason(), versionDecision.unknownFields(),
                versionDecision.warnings());
        if (!versionDecision.accepted()) {
            return versionRejection(versionDecision);
        }

        // 3) 安全重试调用（下游为进程内模拟，无需外部服务）
        boolean write = "write".equalsIgnoreCase(request.operation());
        String idempotencyKey = request.idempotencyKey() != null ? request.idempotencyKey() : routeId + ":" + callerId;
        DownstreamCall<Map<String, Object>> call = simulatedDownstream(request.simulate(), fields);
        RetryOutcome<Map<String, Object>> outcome = write
                ? safeRetryExecutor.executeWrite(call, idempotencyKey)
                : safeRetryExecutor.executeRead(call);

        log.info("最终结果 success={} attempts={} errorType={}",
                outcome.success(), outcome.attempts(), outcome.errorType());
        if (!outcome.success()) {
            return downstreamFailure(outcome);
        }
        return ResponseEntity.ok(new InvokeResponse(true, versionDecision.version(),
                versionDecision.warnings(), outcome.value(), null, null, outcome.attempts()));
    }

    private ResponseEntity<ErrorResponse> versionRejection(VersionDecision d) {
        if (d.rejectReason() == VersionRejectReason.UNKNOWN_VERSION) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("VERSION_NOT_FOUND",
                            "未知 API 版本，不会静默路由到其他版本: " + d.version(), null));
        }
        if (d.rejectReason() == VersionRejectReason.VERSION_SUNSET) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(new ErrorResponse("VERSION_SUNSET",
                            "API 版本已下线: " + d.version(), null));
        }
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("UNKNOWN_FIELDS:" + String.join(",", d.unknownFields()),
                        "请求包含版本 " + d.version() + " 不认识的字段（删除/改名/从未存在）: "
                                + d.unknownFields(), null));
    }

    private ResponseEntity<ErrorResponse> downstreamFailure(RetryOutcome<?> outcome) {
        HttpStatus status = switch (outcome.errorType()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CONNECTION_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
            case BUSINESS_REJECTION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse("DOWNSTREAM:" + outcome.errorType().name(),
                        outcome.safeMessage(), null));
    }

    private static String describe(RejectReason reason) {
        return switch (reason) {
            case CALLER_QUOTA_EXHAUSTED -> "调用方窗口配额已耗尽";
            case CALLER_BURST_EXHAUSTED -> "调用方突发额度已耗尽";
            case ROUTE_CAPACITY_EXHAUSTED -> "服务路由窗口容量已耗尽";
            case ROUTE_BURST_EXHAUSTED -> "服务路由突发额度已耗尽";
        };
    }

    /**
     * 进程内模拟"下游"：通过 simulate 字段选择故障类型，便于本地独立验证。
     */
    private DownstreamCall<Map<String, Object>> simulatedDownstream(String simulate,
                                                                    Map<String, Object> fields) {
        return () -> {
            if (simulate == null || "ok".equalsIgnoreCase(simulate)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("processed", true);
                result.put("echoFields", fields);
                return result;
            }
            switch (simulate.toLowerCase()) {
                case "timeout" -> throw new SocketTimeoutException("simulated timeout [internal detail]");
                case "connect" -> throw new ConnectException("simulated connection refused [internal detail]");
                case "business" -> throw new BusinessRejectionException("余额不足，业务拒绝");
                default -> {
                    // 模拟未预期的内部异常：细节不得透出
                    throw new IllegalStateException("NPE at com.internal.DownstreamClient [internal detail]");
                }
            }
        };
    }
}
