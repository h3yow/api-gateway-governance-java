# API 网关治理层（Java / Spring Boot 3）

面向下游调用方的 API 治理层，在同一套请求链路上提供：

1. **限流与配额**：按「调用方」与「服务路由」两个维度，分别叠加
   **固定窗口配额**（硬上限）与**令牌桶突发**（稳态速率 + 瞬时突发）两种语义；
   每次请求返回**两维度两口径的剩余额度**（响应头 + 响应体），被限流时给出
   **按真实状态推算的 `Retry-After` 等待秒数**，并提供只读运行期**配额快照**端点；
2. **多版本治理**：同一能力多版本并行暴露、生命周期管理（ACTIVE / DEPRECATED / RETIRED）、
   字段级向后兼容与不兼容演进的确定性拒绝；
3. **失败归一化与安全重试**：超时 / 连接失败 / 业务拒绝 / 未知错误映射为可区分错误类型，
   仅对传输类故障重试，写操作通过幂等键保证重试不重复生效，内部细节不外露；
4. **稳定错误三分类**：`rate_limited`（等待后重试）/ `caller_fixable`（改请求，勿重试）
   / `server_fault`（服务端问题），调用方一看即知该怎么做。

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

### 1.4 剩余额度反馈（响应头 + 响应体）

每次请求的结论都会带上**两个维度 × 两种口径**的剩余额度，且与本次判定、实际占用
取自同一次 CAS 结论，因此严格对得上：放行时剩余 = 上限 − 本次占用后已用；被拒时
被拒口径剩余恒为 0，不会出现“还剩 5 却被拒”或“已用尽却放行”。

响应头（成功响应与 429 响应均携带；某口径未启用或维度未判定时该头省略）：

| 响应头 | 口径 | 语义 |
|---|---|---|
| `X-RateLimit-Route-Window-Remaining` | 路由·固定窗口 | 当前窗口内还可再发多少次（硬上限 − 已用），窗口滚动后整体恢复 |
| `X-RateLimit-Route-Burst-Remaining` | 路由·突发容量 | 令牌桶当前可**立即**放行的请求数（向下取整展示，0 表示不能再突发） |
| `X-RateLimit-Caller-Window-Remaining` | 调用方·固定窗口 | 同上，调用方维度 |
| `X-RateLimit-Caller-Burst-Remaining` | 调用方·突发容量 | 同上，调用方维度 |

成功响应体的 `rateLimit` 内嵌对象同样给出这些值（突发口径为精确小数令牌数）：

```json
"rateLimit": {
  "allowed": true, "reason": null, "detail": "allowed",
  "route":  { "windowRemaining": 59, "windowRetryAfterMillis": 0,
              "burstRemaining": 9.0, "burstRetryAfterMillis": 0 },
  "caller": { "windowRemaining": 99, "windowRetryAfterMillis": 0,
              "burstRemaining": 19.0, "burstRetryAfterMillis": 0 },
  "retryAfterMillis": 0
}
```

两种口径语义区分：

- **`windowRemaining`（配额口径）**：离散整数，只在窗口滚动时恢复。窗口内用尽后，
  无论等多久（只要不跨窗口边界）都是 0；跨边界瞬间对所有线程一致归零恢复。
- **`burstRemaining`（突发口径）**：随时间按填充速率连续回补的令牌数（内部为 double，
  响应头向下取整）。小于 1 意味着“已不能再立即放行”，但令牌正在回补。

窗口滚动 / 令牌回补的边界时刻同样由 CAS 快照保证：同一时刻不同线程不会算出互相
打架的数字（并发一致性见 `RateLimitSnapshotConcurrencyTest`）。

### 1.5 被限流后等多久：`Retry-After`

429 响应除保留具体 `errorCode` 外，还提供可直接照做的等待时间，**基于当时真实限流
状态推算，不是固定常量**：

- 标准响应头 `Retry-After: <seconds>`（整秒，向上取整，至少 1 秒）；
- 响应体 `retryAfterSeconds`（与头一致）与 `message`（中文说明，明确“无需修改请求”）。

两种口径各自对应自己的恢复时间：

| 被拒原因 | 等待时间算法 |
|---|---|
| `*_window`（固定窗口） | `窗口结束时刻 − 当前时刻`（滚动后整配额恢复），向上取整到秒 |
| `*_burst`（令牌桶） | `ceil((1 − 桶内现有令牌) / 填充速率 × 1000)`，桶内已有部分回补令牌时等待会相应缩短 |

429 响应体同时带 `"category": "rate_limited"`；此时被拒维度的剩余额度头为 `0`，
而已经通过并真实占用的另一维度（例如路由先过、调用方被拒）其剩余额度照常带回。

### 1.6 只读配额快照：`GET /api/govern/rate-limits`

运维或调用方可随时读取进程内所有**已出现过**的路由 / 调用方维度的配额快照：

```json
{
  "sampledAtMillis": 1790533634931,
  "routes": {
    "orders": {
      "window": { "windowStart": 1790533620000, "windowEnd": 1790533680000,
                  "quota": 60, "used": 23, "remaining": 37 },
      "burst":  { "capacity": 10, "refillPerSecond": 5.0,
                  "availableTokens": 7.2, "usedTokens": 2.8 },
      "allowedTotal": 120, "rejectedTotal": 4
    }
  },
  "callers": { "caller-1": { "...同结构..." } }
}
```

保证：

- **只读零副作用**：不调用任何 CAS、不推进窗口、不占用额度、不改变任何计数；
- **条目内强一致**：每个条目的字段取自同一 `sampledAtMillis` 时刻、限流器内部单个
  不可变状态的投影，读不到更新到一半的状态；恒有 `0 ≤ used ≤ quota`、
  `used + remaining = quota`、`0 ≤ availableTokens ≤ capacity`；
- 累计计数 `allowedTotal / rejectedTotal` 按维度统计（路由维度在调用方维度之前判定，
  因此被调用方限流拒绝的请求也会计入路由维度的用量与拒绝）；
- 不同条目之间不构成跨维度全局同一瞬间视图（读取期间流量继续推进），这是
  “查询不阻塞流量、不改变状态”的明确取舍。窗口滚动后 `window.used` 归零，但
  `allowedTotal` 等累计计数不丢。

---

## 2. 错误三分类（调用方该怎么做）

所有错误响应体在原有 `errorCode / httpStatus / message` 之外新增稳定字段
`category`；限流响应再带 `retryAfterSeconds`：

| category | 含义 | HTTP | 调用方动作 |
|---|---|---|---|
| `rate_limited` | 暂时无额度，请求本身合法 | 429 | 按 `Retry-After` / `retryAfterSeconds` 等待后重发**同一请求**，不要改内容 |
| `caller_fixable` | 调用方自己就能改好的问题 | 4xx | 按 `message` 修改请求后再发；**不要原样重试，也不是服务端故障** |
| `server_fault` | 服务端自身故障 | 5xx | 不要靠改请求解决；传输类故障治理层已在内部安全重试，对外时重试已耗尽，勿盲目重试 |

归为 `caller_fixable` 的情形（稳定、不因 HTTP 状态巧合漂移）：

- 缺必要信息：缺 `X-Caller-Id` / `X-Api-Version` 头（`missing_required_header`，400）、
  请求体非法 JSON（`malformed_request`，400）；
- 版本不符约定：`version_unknown`(404) / `version_deprecated`(400) / `version_retired`(410)；
- 字段不符契约：未知 / 已删除 / 已改名 / 必填缺失（均 400，文案明确指出要改什么、改成什么）；
- 写操作缺防重复标识：`missing_idempotency_key`(400)；
- 同幂等键在途冲突：`idempotency_in_flight`(409，可稍后凭**同一幂等键**查询结果)；
- 下游业务拒绝：`downstream_business_rejected`(422，结果确定，不重试)。

这些错误的 `retryAfterSeconds` 一律缺省（不引导等待重试）；`server_fault`
（超时 504 / 连接失败 502 / 未知错误 500）同样不带限流式等待建议，内部细节仍只入日志。
分类的穷举断言见 `ErrorClassificationTest`。

---

## 3. 版本兼容规则

### 3.1 版本号

请求头 `X-Api-Version`，接受 `v1` / `1` / `v1.2` / `1.2`，统一归一化为 `major.minor`。
非法或缺失一律 `version_unknown`，不猜测、不就近路由。

### 3.2 生命周期

| 状态 | 行为 | HTTP | errorCode |
|---|---|---|---|
| `ACTIVE` | 正常服务 | 200 | — |
| `DEPRECATED` | 拒绝新调用，响应带 `Deprecation: true` 与 `Warning` 头 | 400 | `version_deprecated` |
| `RETIRED` | 确定下线，不静默转发到其他版本 | 410 | `version_retired` |
| 未注册版本 | 精确查询失败 | 404 | `version_unknown` |

### 3.3 字段兼容规则（以演示能力 `orders` 为例）

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

## 4. 失败归一化与安全重试

### 4.1 错误类型映射

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

### 4.2 重试与去重

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

## 5. HTTP 接口

演示能力（写操作）：

- `POST /api/govern/orders`
- `POST /api/govern/profiles`
- `GET  /api/govern/rate-limits` —— 只读配额快照（见 1.6）

请求头：

| 头 | 必填 | 说明 |
|---|---|---|
| `X-Caller-Id` | 是 | 调用方标识（限流维度）；缺失/空白返回 400 `missing_required_header` |
| `X-Api-Version` | 是 | 如 `v1.1`、`v2.0`；缺失/空白同样 400 `missing_required_header` |
| `X-Idempotency-Key` | 写操作必填 | 幂等键 |
| `X-Failure-Scenario` | 否 | 本地故障注入：`timeout` / `connection` / `business` / `unknown` |

成功响应（节选；同时带四个 `X-RateLimit-*-Remaining` 响应头）：

```json
{
  "accepted": true,
  "rateLimit": {
    "allowed": true, "reason": null, "detail": "allowed",
    "route":  { "windowRemaining": 59, "windowRetryAfterMillis": 0,
                "burstRemaining": 9.0, "burstRetryAfterMillis": 0 },
    "caller": { "windowRemaining": 99, "windowRetryAfterMillis": 0,
                "burstRemaining": 19.0, "burstRetryAfterMillis": 0 },
    "retryAfterMillis": 0
  },
  "resolvedVersion": "v2.0",
  "deduplicated": true,
  "attempts": 2,
  "body": { "idempotencyKey": "k-1", "deduplicated": true, "route": "orders" }
}
```

限流失败响应（注意 `category` 与 `retryAfterSeconds`；响应头含
`Retry-After` 与被拒后剩余额度）：

```json
{ "errorCode": "rate_limit_caller_window", "httpStatus": 429,
  "category": "rate_limited", "retryAfterSeconds": 47,
  "message": "caller=caller-1 固定窗口配额已用尽 (...)，预计 47 秒后窗口滚动恢复；请等待 47 秒后再试（无需修改请求）" }
```

调用方可修正失败响应（无 `retryAfterSeconds`）：

```json
{ "errorCode": "payload_unknown_field", "httpStatus": 400,
  "category": "caller_fixable", "retryAfterSeconds": null,
  "message": "版本 v2.0 不包含未知字段 'bogus'，请求不会被静默忽略" }
```

---

## 6. 配置项（`application.properties`）

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

## 7. 本地验证方法

环境要求：JDK 21+、Maven 3.9+（在 JDK 25 上同样可构建）。

```bash
# 1) 全量测试（50 个用例），单测日志会打印 请求输入 | 判定依据 | 最终结果
mvn test

# 2) 只跑关键场景
mvn test -Dtest=RateLimitConcurrencyTest          # 并发限流不超额 + 四原因区分
mvn test -Dtest=RateLimitQuotaObservationTest     # 两口径剩余额度 + 等待时间推算
mvn test -Dtest=RateLimitSnapshotConcurrencyTest  # 高并发结论一致性 + 快照强一致/零副作用
mvn test -Dtest=ErrorClassificationTest           # 限流/调用方/服务端 三分类穷举
mvn test -Dtest=ObservabilityHttpIntegrationTest  # HTTP：剩余额度头、Retry-After、快照端点
mvn test -Dtest=VersionBoundaryTest               # 版本生命周期 + 字段兼容边界
mvn test -Dtest=RetryDeduplicationTest            # 重试安全 + 并发幂等去重
mvn test -Dtest=GovernanceIntegrationTest         # 真实 HTTP 端到端
mvn test -Dtest=RateLimitHttpIntegrationTest      # HTTP 层 429 原因码序列

# 3) 启动服务后用 curl 手动验证
mvn spring-boot:run
```

手动验证示例：

```bash
# 正常写调用（v2.0），观察四个 X-RateLimit-*-Remaining 响应头
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-1' -H 'Content-Type: application/json' \
  -d '{"itemId":"sku-1","quantity":2}'

# 连续打满调用方配额（默认 100/分钟，可用独立 caller 反复试），
# 被拒响应带 Retry-After: <秒>、category=rate_limited 与剩余额度头
for i in $(seq 1 102); do
  curl -s -o /dev/null -D - -X POST localhost:8080/api/govern/orders \
    -H "X-Caller-Id: burst-demo" -H 'X-Api-Version: v2.0' \
    -H "X-Idempotency-Key: k-$i" -H 'Content-Type: application/json' \
    -d '{"itemId":"sku-1","quantity":1}' | grep -Ei 'HTTP/|retry-after|x-ratelimit'
done

# 只读配额快照（不改任何计数，可反复执行对比）
curl -s localhost:8080/api/govern/rate-limits | python3 -m json.tool

# 调用方可修正错误：category=caller_fixable，文案指出要改什么
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-2' -H 'Content-Type: application/json' \
  -d '{"itemId":"sku-1","quantity":1,"promoCode":"X"}'

# 服务端故障：category=server_fault（与调用方错误严格区分）
curl -i -X POST localhost:8080/api/govern/orders \
  -H 'X-Caller-Id: caller-1' -H 'X-Api-Version: v2.0' \
  -H 'X-Idempotency-Key: demo-3' -H 'X-Failure-Scenario: connection' \
  -H 'Content-Type: application/json' -d '{"itemId":"sku-1","quantity":1}'
```

### 等待时间语义如何本地验证

- **固定窗口**：`window-millis` 调小（如 1000ms），配额打满后立刻看 `Retry-After`，
  其值应约等于“距当前秒结束的秒数”；跨过窗口边界后请求立即恢复（配额整额回来）。
- **突发容量**：`burst-capacity=2, refill-per-second=1`，打满后立刻被拒（约等 1s），
  若先等 500ms 再请求，桶内约有 0.5 枚令牌仍被拒，但 `retryAfterMillis` 缩到约 500ms
  （精确断言见 `RateLimitQuotaObservationTest.burstRetryWaitIsComputedFromRealRefillState`）。

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

## 8. 代码结构

```
governance/
├── common/        ErrorCode（稳定错误码+HTTP 映射+ErrorCategory 三分类）、ErrorCategory、
│                  GovernanceException、ErrorResponse（category/retryAfterSeconds）
├── ratelimit/     FixedWindowQuotaLimiter（acquire 原子带回剩余/等待/视图）、
│                  TokenBucketLimiter（同上）、RateLimitService（判定+维度计数+snapshot）、
│                  RateLimitDecision（两维度两口径剩余额度+重试等待）、RateLimitSnapshot、
│                  RateLimitRejectedException、原因枚举
├── version/       ApiVersion、VersionRegistry、VersionRoutingService、PayloadValidator、契约模型
├── retry/         RetryExecutor、RetryPolicy、IdempotencyStore/InMemoryIdempotencyStore
├── downstream/    SimulatedDownstreamService（本地可复现下游 + 幂等台账 + 故障注入）
├── orchestration/ GovernanceOrchestrator（限流→版本→字段→幂等→重试 的确定编排）
├── web/           GovernanceController（剩余额度头 + GET 快照端点）、GlobalExceptionHandler
│                  （Retry-After/分类归一化 + 缺头/非法体显式 caller_fixable + 脱敏）
└── config/        RateLimitProperties、RetryProperties、GovernanceConfig（版本契约注册）
```

