# API 网关治理层（Java / Spring Boot 3）

面向下游调用方的 API 治理层，在同一套请求链路上提供：

1. **限流与配额**：按「调用方」与「服务路由」两个维度，分别叠加
   **固定窗口配额**（硬上限）与**令牌桶突发**（稳态速率 + 瞬时突发）两种语义；
2. **多版本治理**：同一能力多版本并行暴露、生命周期管理（ACTIVE / DEPRECATED / RETIRED）、
   字段级向后兼容与不兼容演进的确定性拒绝；
3. **失败归一化与安全重试**：超时 / 连接失败 / 业务拒绝 / 未知错误映射为可区分错误类型，
   仅对传输类故障重试，写操作通过幂等键保证重试不重复生效，内部细节不外露。

全部能力仅依赖 Spring Boot Web 与 JDK，无外部服务、真实凭据或付费资源，可本地独立复现。

---

## 1. 限流模型

### 1.1 两个维度 × 两种语义

每个请求依次经过 **4 个判定点**，顺序确定、短路返回：

```
① 服务路由 · 固定窗口配额   ② 服务路由 · 令牌桶突发
③ 调用方   · 固定窗口配额   ④ 调用方   · 令牌桶突发
```

- **固定窗口配额（quota / window）**：窗口边界为
  `floor(now / windowMillis) * windowMillis`，窗口内计数达到配额即拒绝，窗口滚动归零。
  这是**硬上限**，用于配额控制。其明确取舍是不做滑动平滑，相邻窗口临界点附近最多 2x 瞬时流量。
- **令牌桶突发（capacity / refill-per-second）**：桶容量即允许的瞬时突发请求上限，
  按固定速率填充，稳态平均速率不超过填充速率，桶空拒绝。

### 1.2 并发正确性

- 固定窗口的 `(windowStart, count)` 是**单个不可变快照**，用一个
  `AtomicReference` + CAS 更新——“跨窗口归零”和“窗口内计数”不会被并发撕裂，
  既不超额放行，也不会因读到半更新状态而误拒。
- 令牌桶的 `(tokens, lastRefillMillis)` 同样是 CAS 快照，每枚令牌只会被一个请求取走。
- 限流器按维度 key 懒加载并缓存在 `ConcurrentHashMap` 中，进程内并发不会丢失或错算状态。

并发压测见 `RateLimitConcurrencyTest`（64 线程 × 20 请求打 500 配额，放行恰好 = 500）。

### 1.3 被拒绝时如何区分原因

返回 HTTP 429 与稳定机器码 `errorCode`，四种原因互不混淆：

| errorCode | 含义 |
|---|---|
| `rate_limit_route_window` | 服务路由固定窗口配额用尽 |
| `rate_limit_route_burst` | 服务路由瞬时突发超过令牌桶 |
| `rate_limit_caller_window` | 调用方固定窗口配额用尽 |
| `rate_limit_caller_burst` | 调用方瞬时突发超过令牌桶 |

> 说明：请求在某一维度“通过”时也会占用该维度的计数。因此当调用方配额更小时，
> 后续请求会先收到 `rate_limit_caller_window`；待路由窗口计数也耗尽后会收到
> `rate_limit_route_window`（见 `RateLimitHttpIntegrationTest` 的完整序列）。

---

## 2. 版本兼容规则

### 2.1 版本号

请求头 `X-Api-Version`，接受 `v1` / `1` / `v1.2` / `1.2`，统一归一化为 `major.minor`。
非法或缺失一律 `version_unknown`，不猜测、不就近路由。

### 2.2 生命周期

| 状态 | 行为 | HTTP | errorCode |
|---|---|---|---|
| `ACTIVE` | 正常服务 | 200 | — |
| `DEPRECATED` | 拒绝新调用，响应带 `Deprecation: true` 与 `Warning` 头 | 400 | `version_deprecated` |
| `RETIRED` | 确定下线，不静默转发到其他版本 | 410 | `version_retired` |
| 未注册版本 | 精确查询失败 | 404 | `version_unknown` |

### 2.3 字段兼容规则（以演示能力 `orders` 为例）

- **只增不删是兼容演进**：v1.1 相对 v1.0 新增非必填字段 `contactPhone`，
  旧载荷在新版本上继续合法（v1.0 字段集是 v1.1 字段集的子集）。
- **删除 / 改名是不兼容演进**，必须显式发生在新版本，且绝不静默接受：
  - v2.0 删除 `promoCode` → 携带时报 `payload_field_removed`；
  - v2.0 把 `couponCode` 改名为 `discountCode` → 携带旧名时报
    `payload_field_renamed`，文案明确指向新字段名；
  - 契约外字段 → `payload_unknown_field`；必填缺失 → `payload_missing_field`。
- 校验顺序确定：**删除 → 改名 → 未知 → 缺失**。

版本契约在 `GovernanceConfig` 中以 `VersionDefinition` 显式注册（无外部注册中心依赖）。

---

## 3. 失败归一化与安全重试

### 3.1 错误类型映射

| 归一化类型 | 可重试 | HTTP | errorCode |
|---|---|---|---|
| 超时 TIMEOUT | 是 | 504（重试耗尽） | `downstream_timeout` |
| 连接失败 CONNECTION_FAILED | 是 | 502（重试耗尽） | `downstream_connection_failed` |
| 业务拒绝 BUSINESS_REJECTED | 否（结果确定） | 422 | `downstream_business_rejected` |
| 未知错误 UNKNOWN | 否（避免放大副作用） | 500 | `downstream_unknown_error` |
| 幂等键冲突（同键在途） | — | 409 | `idempotency_in_flight` |
| 写操作缺幂等键 | — | 400 | `missing_idempotency_key` |

内部异常（堆栈、下游地址、`internal-detail-*` 等）只写入服务端日志，
响应体只含稳定 `errorCode / httpStatus / message`。

### 3.2 重试与去重

- 仅 TIMEOUT / CONNECTION_FAILED 重试，退避为固定 `backoff-millis`（确定性，便于本地复现）。
- **写操作必须携带 `X-Idempotency-Key`**，入口前置校验，缺失直接拒绝。
- 两层去重：
  1. **治理层入口**（`InMemoryIdempotencyStore`）：同一幂等键并发/重复请求只执行一次，
     后来者等待首个结果并重放（`Replayed`），仍在途则返回 409（`InFlight`）；
  2. **下游侧**（模拟真实下游的幂等台账）：同一键只生效一次。
     针对“**下游已生效但响应超时丢失**”的模糊场景，重试命中台账返回同一引用且
     `deduplicated=true`，业务绝不二次生效（见
     `RetryDeduplicationTest.timeoutIsRetriedAndWriteEffectHappensOnce`）。

---

## 4. HTTP 接口

演示能力（写操作）：

- `POST /api/govern/orders`
- `POST /api/govern/profiles`

请求头：

| 头 | 必填 | 说明 |
|---|---|---|
| `X-Caller-Id` | 是 | 调用方标识（限流维度） |
| `X-Api-Version` | 是 | 如 `v1.1`、`v2.0` |
| `X-Idempotency-Key` | 写操作必填 | 幂等键 |
| `X-Failure-Scenario` | 否 | 本地故障注入：`timeout` / `connection` / `business` / `unknown` |

成功响应（节选）：

```json
{
  "accepted": true,
  "rateLimit": { "allowed": true, "reason": null, "detail": "allowed" },
  "resolvedVersion": "v2.0",
  "deduplicated": true,
  "attempts": 2,
  "body": { "idempotencyKey": "k-1", "deduplicated": true, "route": "orders" }
}
```

失败响应：

```json
{ "errorCode": "rate_limit_caller_window", "httpStatus": 429,
  "message": "caller=... 固定窗口配额已用尽 (...)" }
```

---

## 5. 配置项（`application.properties`）

| 键 | 默认 | 说明 |
|---|---|---|
| `governance.rate-limit.defaults.window-quota` | 100 | 默认固定窗口配额，-1 禁用 |
| `governance.rate-limit.defaults.window-millis` | 60000 | 窗口长度（毫秒） |
| `governance.rate-limit.defaults.burst-capacity` | 20 | 令牌桶容量，-1 禁用 |
| `governance.rate-limit.defaults.refill-per-second` | 10 | 令牌填充速率 |
| `governance.rate-limit.routes.<route>.*` | — | 路由级覆盖（如 `orders`），未配置项继承 defaults |
| `governance.rate-limit.callers.<caller>.*` | — | 调用方级覆盖（如 `vip`） |
| `governance.retry.max-attempts` | 3 | 最大尝试次数（含首次），仅对超时/连接失败生效 |
| `governance.retry.backoff-millis` | 20 | 固定退避毫秒 |

规则合并语义：覆盖规则中未显式配置的项（哨兵值 -1）回退到 defaults。

---

## 6. 本地验证方法

环境要求：JDK 21+、Maven 3.9+（在 JDK 25 上同样可构建）。

```bash
# 1) 全量测试（29 个用例），单测日志会打印 请求输入 | 判定依据 | 最终结果
mvn test

# 2) 只跑关键场景
mvn test -Dtest=RateLimitConcurrencyTest      # 并发限流不超额 + 四原因区分
mvn test -Dtest=VersionBoundaryTest           # 版本生命周期 + 字段兼容边界
mvn test -Dtest=RetryDeduplicationTest        # 重试安全 + 并发幂等去重
mvn test -Dtest=GovernanceIntegrationTest     # 真实 HTTP 端到端
mvn test -Dtest=RateLimitHttpIntegrationTest  # HTTP 层 429 原因码序列

# 3) 启动服务后用 curl 手动验证
mvn spring-boot:run
```

手动验证示例：

```bash
# 正常写调用（v2.0）
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-1' -H 'Content-Type: application/json' \
  -d '{"itemId":"sku-1","quantity":2}'

# 超时重试 + 幂等去重（首次超时后重试成功，deduplicated=true）
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-timeout' -H 'X-Failure-Scenario: timeout' \
  -H 'Content-Type: application/json' -d '{"itemId":"sku-1","quantity":1}'

# 已删除字段（确定性拒绝，不静默接受）
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-2' -H 'Content-Type: application/json' \
  -d '{"itemId":"sku-1","quantity":1,"promoCode":"X"}'
```

### 测试日志样例

```
[固定窗口配额并发] input=threads=64 perThread=20 quota=500
  | basis=CAS 原子快照计数，窗口内硬上限 500
  | result=allowed=500 rejected=780
[超时重试去重] input=write key=key-timeout scenario=timeout, maxAttempts=3
  | basis=TIMEOUT 可重试；下游按幂等键只生效一次，重试命中台账
  | result=成功 ref=orders-ref-1 effectCount=1 invocationCount=2
```

---

## 7. 代码结构

```
governance/
├── common/        ErrorCode（稳定错误码+HTTP 映射）、GovernanceException、ErrorResponse
├── ratelimit/     FixedWindowQuotaLimiter、TokenBucketLimiter、RateLimitService、原因枚举
├── version/       ApiVersion、VersionRegistry、VersionRoutingService、PayloadValidator、契约模型
├── retry/         RetryExecutor、RetryPolicy、IdempotencyStore/InMemoryIdempotencyStore
├── downstream/    SimulatedDownstreamService（本地可复现下游 + 幂等台账 + 故障注入）
├── orchestration/ GovernanceOrchestrator（限流→版本→字段→幂等→重试 的确定编排）
├── web/           GovernanceController、GlobalExceptionHandler（归一化 + 脱敏）
└── config/        RateLimitProperties、RetryProperties、GovernanceConfig（版本契约注册）
```
