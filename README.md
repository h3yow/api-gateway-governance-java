# API 网关治理层（Java / Spring Boot 3）

面向下游调用方的 API 治理层，在同一套请求链路上提供：

1. **限流与配额**：按「调用方」与「服务路由」两个维度，分别叠加
   **固定窗口配额**（硬上限）与**令牌桶突发**（稳态速率 + 瞬时突发）两种语义；
2. **多版本治理**：同一能力多版本并行暴露、生命周期管理（ACTIVE / DEPRECATED / RETIRED）、
   字段级向后兼容与不兼容演进的确定性拒绝；
3. **失败归一化与安全重试**：超时 / 连接失败 / 业务拒绝 / 未知错误映射为可区分错误类型，
   仅对传输类故障重试，写操作通过幂等键保证重试不重复生效，内部细节不外露；
4. **可观测 + 可执行反馈**：每次结论携带两个维度两种口径的**剩余额度**
   （响应体 + `X-*-Remaining` 响应头）；限流拒绝给出基于真实状态的 **`Retry-After`**；
   错误按 **`rate_limited / client_error / server_error`** 稳定分类；
   并提供只读运行期配额快照 `GET /api/govern/quotas`。

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

### 1.4 剩余额度反馈（可观测）

每次请求（**放行和被拒都包括**）都会在结论里带上「服务路由 / 调用方」两个维度、
区分两种口径的剩余额度，成功时在响应体 `rateLimit.quota` 与下列响应头中同时给出：

| 响应头 | 口径 | 含义 |
|---|---|---|
| `X-Route-Window-Remaining` | 路由 · 固定窗口 | 当前窗口滚动重置前，路由维度还能放行的请求数（非负整数） |
| `X-Route-Burst-Remaining` | 路由 · 令牌桶 | 路由维度当前可用突发令牌数（保留 3 位小数） |
| `X-Caller-Window-Remaining` | 调用方 · 固定窗口 | 调用方维度窗口剩余配额（非负整数） |
| `X-Caller-Burst-Remaining` | 调用方 · 令牌桶 | 调用方维度当前可用突发令牌数（保留 3 位小数） |

**两种口径的语义区别（务必分开理解）：**

- **固定窗口配额（window）= 硬上限**：`remaining = limit - used`。窗口滚动到
  `resetAtMillis` 时 `used` 立即归零、`remaining` 恢复为 `limit`，是“到点一次性恢复”。
- **突发容量（burst）= 令牌桶**：`availableTokens` 随时间**连续回补**
  （每秒 `refillTokensPerSecond` 枚），桶满后新令牌丢弃、不累积；
  不存在“某个固定时刻一次性恢复”。

**自洽性保证：** 放行结论里的余额是“本次请求实际占用之后”的余额（因此放行后窗口余额
必然严格小于上限、桶余额比请求前少 1）；被拒结论里被打满口径的剩余必然为 0。
判定结果、余额与实际占用来自同一次原子 CAS，绝不出现“还剩 5 却被拒”或“已用尽仍放行”。
未启用的口径不输出对应字段/响应头（序列化为 `null` 或缺头）。

> 当请求在路由维度被短路拒绝时，调用方维度完全没有参与判定，其额度以**只读**方式呈现，
> 不会因为这次拒绝而被占用。

### 1.5 被限流后的等待时间（可执行反馈）

限流拒绝（HTTP 429）除了保留可区分的具体 `errorCode`，还给出调用方可直接照做的等待时间：

- 响应头：`Retry-After: <seconds>`（标准整秒，向上取整，保证至少等到恢复时刻）；
- 响应体：`retryAfterSeconds`（与头部一致）与内部毫秒值。

等待时间基于**当时的真实限流状态**推算，不是固定常量：

- 命中**固定窗口配额**：`retryAfter = resetAtMillis - now`，即等到当前窗口滚动；
- 命中**令牌桶突发**：`retryAfter = ceil((1 - 当前可用令牌) / refillTokensPerSecond)`，
  即按填充速率补出第 1 枚令牌所需时间（填充越快，等待越短）。

### 1.6 错误一级分类：该等、该改、还是服务端问题

所有错误响应体新增 `category` 与 `retryable`，把“调用方自己能改好的问题”和
“服务端自身故障”彻底分开：

| category | retryable | 包含的 errorCode | 调用方该怎么做 |
|---|---|---|---|
| `rate_limited` | `true` | 4 个 `rate_limit_*` | 等 `Retry-After` 秒后**原样重试** |
| `client_error` | `false` | `version_unknown/version_deprecated/version_retired`、`payload_*`、`missing_idempotency_key`、`idempotency_in_flight`、`downstream_business_rejected`，以及缺必传头/非法 JSON 的 `invalid_request` | **先按 message 修改请求**再提交，不要原样重试、更不用排查服务端 |
| `server_error` | `false` | `downstream_timeout/connection_failed/unknown_error` | 网关已按自身策略重试，结论确定，调用方不要立即重试 |

`message` 对调用方可执行：缺哪个头、哪个字段在哪个版本被删/改名、要改用哪个版本，
都会明确写出；内部堆栈、下游地址等细节仍只入服务端日志。

### 1.7 运行期只读配额快照

`GET /api/govern/quotas` 返回所有已出现过的「路由 / 调用方」维度的实时配额快照：

```json
{
  "capturedAtMillis": 1790534658767,
  "routes": {
    "orders": {
      "key": "orders",
      "window": { "limit": 60, "used": 12, "remaining": 48,
                  "windowStartMillis": 1790534640000, "resetAtMillis": 1790534700000 },
      "burst": { "capacity": 10, "availableTokens": 9.0,
                 "usedTokens": 1.0, "refillTokensPerSecond": 5.0 },
      "totalAllowed": 12,
      "totalRejected": 1
    }
  },
  "callers": { "...": "同结构" }
}
```

- `window.used` 永不超过 `limit`；`burst.availableTokens` 永不超过 `capacity`、不为负；
- `totalAllowed / totalRejected` 是该维度自进程启动起的**累计**计数（跨窗口不清零）；
  在路由维度被短路的请求不计入调用方维度的任一计数；
- 查询**只读**：不占用配额、不触发窗口翻转或令牌落盘、不改变任何计数，
  也不会因为查询而凭空创建一个新维度；每个维度的字段取自同一不可变快照，
  并发下读不到更新到一半的状态。

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
- `GET  /api/govern/quotas` —— 只读运行期配额快照（见 1.7）

请求头：

| 头 | 必填 | 说明 |
|---|---|---|
| `X-Caller-Id` | 是 | 调用方标识（限流维度），缺失返回 400 `invalid_request` |
| `X-Api-Version` | 是 | 如 `v1.1`、`v2.0` |
| `X-Idempotency-Key` | 写操作必填 | 幂等键 |
| `X-Failure-Scenario` | 否 | 本地故障注入：`timeout` / `connection` / `business` / `unknown` |

成功响应头（剩余额度，见 1.4）：

```
X-Route-Window-Remaining: 59
X-Route-Burst-Remaining: 9.000
X-Caller-Window-Remaining: 99
X-Caller-Burst-Remaining: 19.000
```

成功响应（节选，`rateLimit.quota` 与上面的头同源）：

```json
{
  "accepted": true,
  "rateLimit": {
    "allowed": true, "reason": null, "detail": "allowed",
    "quota": {
      "route":  { "window": { "limit": 60, "used": 1, "remaining": 59,
                              "windowStartMillis": 1790534640000,
                              "resetAtMillis": 1790534700000 },
                  "burst":  { "capacity": 10, "availableTokens": 9.0,
                              "usedTokens": 1.0, "refillTokensPerSecond": 5.0 } },
      "caller": { "window": { "limit": 100, "used": 1, "remaining": 99, "...": "..." },
                  "burst":  { "capacity": 20, "availableTokens": 19.0, "...": "..." } }
    },
    "retryAfterMillis": 0
  },
  "resolvedVersion": "v2.0",
  "deduplicated": true,
  "attempts": 2,
  "body": { "idempotencyKey": "k-1", "deduplicated": true, "route": "orders" }
}
```

限流失败响应（429，注意 `Retry-After` 头与 `retryAfterSeconds`/`quota` 体）：

```
HTTP/1.1 429
Retry-After: 42
```
```json
{
  "errorCode": "rate_limit_route_window",
  "category": "rate_limited",
  "httpStatus": 429,
  "retryable": true,
  "retryAfterSeconds": 42,
  "message": "route=orders 固定窗口配额已用尽 (...)",
  "quota": { "route": { "...": "剩余额度，结构同成功体" },
             "caller": { "...": "短路维度只读快照" } }
}
```

调用方可修正的问题（4xx，`category=client_error`、`retryable=false`）：

```json
{ "errorCode": "missing_idempotency_key", "category": "client_error",
  "httpStatus": 400, "retryable": false,
  "message": "写操作必须提供 X-Idempotency-Key 幂等键，否则不予执行以防重复生效" }
```

服务端故障（5xx，`category=server_error`、`retryable=false`，内部细节已脱敏）：

```json
{ "errorCode": "downstream_unknown_error", "category": "server_error",
  "httpStatus": 500, "retryable": false,
  "message": "下游发生未归类错误，未进行重试" }
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
# 1) 全量测试（55 个用例），单测日志会打印 请求输入 | 判定依据 | 最终结果
mvn test

# 2) 只跑关键场景
mvn test -Dtest=RateLimitConcurrencyTest          # 并发限流不超额 + 四原因区分
mvn test -Dtest=RemainingQuotaTest                # 两口径剩余额度（滚动/回补边界）
mvn test -Dtest=RateLimitFeedbackTest             # 拒绝等待时间 + 两维度额度自洽 + 快照只读
mvn test -Dtest=QuotaConsistencyConcurrencyTest   # 高并发结论/计数/快照三方一致不超额
mvn test -Dtest=ErrorClassificationTest           # rate_limited/client_error/server_error 分类契约
mvn test -Dtest=ObservabilityHttpIntegrationTest  # HTTP：429 Retry-After/额度头/快照端点
mvn test -Dtest=VersionBoundaryTest               # 版本生命周期 + 字段兼容边界
mvn test -Dtest=RetryDeduplicationTest            # 重试安全 + 并发幂等去重
mvn test -Dtest=GovernanceIntegrationTest         # 真实 HTTP 端到端
mvn test -Dtest=RateLimitHttpIntegrationTest      # HTTP 层 429 原因码序列

# 3) 启动服务后用 curl 手动验证
mvn spring-boot:run
```

手动验证示例（在上面的正常调用之外，重点看新增反馈）：

```bash
# 剩余额度响应头（成功时也能看到本次占用后的余额）
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-1' -H 'Content-Type: application/json' \
  -d '{"itemId":"sku-1","quantity":2}'
# 关注响应头：X-Route-Window-Remaining / X-Route-Burst-Remaining /
#            X-Caller-Window-Remaining / X-Caller-Burst-Remaining

# 被限流时的可执行反馈：连续打满配额后观察 Retry-After 与 body.category/quota
for i in $(seq 1 70); do
  curl -s -o /dev/null -D - -X POST localhost:8080/api/govern/orders \
    -H 'X-Caller-Id: caller-burst' -H 'X-Api-Version: v2.0' \
    -H "X-Idempotency-Key: burst-$i" -H 'Content-Type: application/json' \
    -d '{"itemId":"sku-1","quantity":1}' | grep -iE 'HTTP/|Retry-After'
done | tail -6
# 429 响应：Retry-After: <秒>，body 含 category=rate_limited、retryable=true、
#           retryAfterSeconds、quota（route/caller × window/burst）

# 调用方错误 vs 服务端故障（category 字段）
curl -s -X POST localhost:8080/api/govern/profiles \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v1.1' \
  -H 'X-Idempotency-Key: p1' -H 'Content-Type: application/json' \
  -d '{"userId":"u","email":"a@b","bogus":1}'      # -> category=client_error
curl -s -X POST localhost:8080/api/govern/profiles \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v1.1' \
  -H 'X-Idempotency-Key: p2' -H 'X-Failure-Scenario: unknown' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u","email":"a@b"}'                 # -> category=server_error

# 只读配额快照（不消耗额度，可连续调用对比计数不变）
curl -s localhost:8080/api/govern/quotas | python3 -m json.tool
```

> 想确定性地观察 429 与 Retry-After，可用极小配额启动：
> `mvn spring-boot:run -Dspring-boot.run.arguments=
> "--governance.rate-limit.routes.orders.window-quota=1,
> --governance.rate-limit.routes.orders.window-millis=100000"`
> 第 2 个请求即返回 429，窗口口径 `Retry-After` 约为 100 秒。

```bash
# 超时重试 + 幂等去重（首次超时后重试成功，deduplicated=true）
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-timeout' -H 'X-Failure-Scenario: timeout' \
  -H 'Content-Type: application/json' -d '{"itemId":"sku-1","quantity":1}'

# 已删除字段（确定性拒绝，不静默接受；category=client_error）
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
├── common/        ErrorCode（稳定错误码+HTTP 映射+一级分类）、ErrorCategory、GovernanceException、ErrorResponse
├── ratelimit/     FixedWindowQuotaLimiter、TokenBucketLimiter（acquire 返回判定+占用后余额+恢复等待，peek 只读）
│                  RateLimitService（两维度判定+剩余额度+真实等待+只读 snapshot）、
│                  RateLimitDecision/QuotaReport/ScopeQuota（请求级反馈）、
│                  RateLimitSnapshot/ScopeSnapshot（运维只读快照）、原因枚举
├── version/       ApiVersion、VersionRegistry、VersionRoutingService、PayloadValidator、契约模型
├── retry/         RetryExecutor、RetryPolicy、IdempotencyStore/InMemoryIdempotencyStore
├── downstream/    SimulatedDownstreamService（本地可复现下游 + 幂等台账 + 故障注入）
├── orchestration/ GovernanceOrchestrator（限流→版本→字段→幂等→重试 的确定编排）
├── web/           GovernanceController、QuotaSnapshotController（GET /api/govern/quotas）、
│                  GlobalExceptionHandler（分类+Retry-After+缺头/坏 JSON 归一+脱敏）、
│                  QuotaHeaderAdvice（成功响应写入 X-*-Remaining 头）
└── config/        RateLimitProperties、RetryProperties、GovernanceConfig（版本契约注册）
```
