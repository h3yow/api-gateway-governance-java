# API 网关治理层（Java / Spring Boot）

面向下游调用方的 API 治理层，在同一个请求流水线中提供：

1. **两维度限流与配额**（调用方 × 服务路由）
2. **多版本并行暴露与版本生命周期管理**（字段兼容规则）
3. **下游失败归一化与安全重试**（写操作幂等去重）

所有行为在并发与异常场景下都有确定、可解释的结论，不依赖任何外部服务，
可在本地独立复现验证。

## 1. 限流模型

代码：`ratelimit/` 包。

### 1.1 维度

| 维度 | 键 | 含义 |
| --- | --- | --- |
| 调用方 | 请求头 `X-Caller-Id` | 单个调用方跨所有路由的总配额 |
| 服务路由 | 请求体 `routeId`（默认 `orders`） | 单个服务路由跨所有调用方的总容量 |

两个维度各自持有独立令牌桶；判定按"调用方优先"顺序执行，
只有调用方维度放行时才消耗路由维度额度。任一维度耗尽即拒绝。

### 1.2 窗口与突发语义

每个维度是一个**令牌桶**：

- 稳态速率：`windowLimit / window`（窗口语义，如 10 次/秒）；
- 桶容量：`windowLimit + burstCapacity`（突发语义）。
  进程启动或键首次出现时桶为满；令牌按时间恒定补充，上限为桶容量。
  `burstCapacity` 是允许超过稳态速率的瞬时余量，为 0 时退化为严格的固定窗口等效速率。
- 拒绝时返回 `Retry-After` 建议等待毫秒数（距下一个令牌可用的时间）。

令牌基于时间戳惰性补充，所有状态变更在桶对象实例锁内完成；
桶按键存放在 `ConcurrentHashMap` 中，`computeIfAbsent` 保证同键桶只创建一次。
因此**进程内并发不会超额放行，限流状态也不会丢失或错算**（见并发测试：100 线程 / 5 配额，放行数恰好为 5）。

### 1.3 可区分的拒绝原因

| 错误码 | 原因 |
| --- | --- |
| `RATE_LIMITED:CALLER_QUOTA_EXHAUSTED` | 调用方窗口配额耗尽（未配置突发时） |
| `RATE_LIMITED:CALLER_BURST_EXHAUSTED` | 调用方突发额度耗尽 |
| `RATE_LIMITED:ROUTE_CAPACITY_EXHAUSTED` | 路由窗口容量耗尽 |
| `RATE_LIMITED:ROUTE_BURST_EXHAUSTED` | 路由突发额度耗尽 |

HTTP 状态码为 `429`，响应体携带 `retryAfterMillis`。

## 2. 版本兼容规则

代码：`versioning/` 包。

### 2.1 生命周期

| 状态 | 行为 |
| --- | --- |
| `ACTIVE` | 正常服务 |
| `DEPRECATED` | 仍服务，响应携带弃用警告（`warnings`），确定可解释 |
| `SUNSET` | 一律拒绝（HTTP 410 `VERSION_SUNSET`），**不静默路由到其他版本** |

可通过 `VersionRegistry.updateStatus(version, status)` 做 `ACTIVE -> DEPRECATED -> SUNSET` 流转。

### 2.2 字段兼容规则（确定且不静默）

- **新增字段必须向后兼容**：新版本只能在已有在服版本的字段集上新增字段。
  注册新版本时若删除/改名了在服字段，注册直接失败（fail-fast，启动期即暴露）。
  已下线（SUNSET）版本不参与该兼容门。
- **未知版本显式拒绝**（HTTP 404 `VERSION_NOT_FOUND`），不做默认版本回退。
- **未知字段显式拒绝**（HTTP 400 `UNKNOWN_FIELDS:<字段名>`）：
  请求中出现该版本字段集之外的字段——无论是字段删除、改名还是从未存在——
  都不会被静默忽略或静默路由到其他版本，响应明确指出字段名。

演示能力 `orders` 的版本族（启动时注册）：

| 版本 | 状态 | 合法字段 |
| --- | --- | --- |
| v0 | SUNSET | name |
| v1 | DEPRECATED | name, amount |
| v2 | ACTIVE | name, amount, remark（v2 新增） |

## 3. 下游失败归一化与安全重试

代码：`resilience/` 包。

### 3.1 错误类型归一化

| 原始异常 | 归一化类型 | HTTP 错误码 | 是否默认可重试 |
| --- | --- | --- | --- |
| `SocketTimeoutException` / `TimeoutException` | `TIMEOUT` | 504 `DOWNSTREAM:TIMEOUT` | 读：是；写：**否** |
| `ConnectException` / `NoRouteToHostException` | `CONNECTION_FAILURE` | 503 `DOWNSTREAM:CONNECTION_FAILURE` | 是（请求确定未送达） |
| `BusinessRejectionException` | `BUSINESS_REJECTION` | 422 `DOWNSTREAM:BUSINESS_REJECTION` | 否（结果确定） |
| 其他未预期异常 | `INTERNAL` | 500 `DOWNSTREAM:INTERNAL` | 否 |

**内部异常细节（堆栈、内部类名、原始下游信息）只写入服务端日志，不进入响应体**；
调用方只看到按类型固定的安全文案。

### 3.2 安全重试与写去重

- 读操作天然幂等：可对策略允许的错误类型重试（默认超时 + 连接失败）。
- 写操作非幂等：
  - **超时不重试**：超时意味着下游可能已收到请求，重复执行可能造成重复生效；
  - **连接失败可在唯一执行权内重试**：连接失败表示请求确定未送达；
  - `IdempotencyStore` 以幂等键折叠调用：同一键的并发重复请求只有一次实际执行，
    终态结果（成功或失败）永久缓存，后续同键调用（含首次失败后的重放）直接复用结果。
    因此写操作的重试/重放不会重复生效。

## 4. 配置项（`src/main/resources/application.properties`，前缀 `governance`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `governance.caller.window-limit` | 10 | 调用方维度每窗口请求数 |
| `governance.caller.window` | 1s | 调用方窗口长度（Duration） |
| `governance.caller.burst-capacity` | 0 | 调用方突发额度 |
| `governance.route.window-limit` | 20 | 路由维度每窗口请求数 |
| `governance.route.window` | 1s | 路由窗口长度 |
| `governance.route.burst-capacity` | 5 | 路由突发额度 |
| `governance.retry.read-max-attempts` | 3 | 读操作最大尝试次数（含首次） |
| `governance.retry.write-max-attempts` | 2 | 写操作最大尝试次数（仅连接失败生效） |
| `governance.retry.backoff-millis` | 10 | 重试退避毫秒数 |

## 5. HTTP 接口

```
POST /api/governance/invoke
Header: X-Caller-Id: <调用方标识>（必填）
        X-Api-Version: v1 | v2（v0 已下线；其他版本 404）
Body:
{
  "routeId": "orders",
  "operation": "read | write",
  "idempotencyKey": "可选，写操作建议提供",
  "simulate": "ok | timeout | connect | business | null",
  "fields": { "name": "...", "amount": 1, "remark": "..." }
}
```

`simulate` 用于在**进程内模拟下游故障**，无需任何外部服务即可复现全部错误路径。

## 6. 本地验证

### 6.1 单元/集成测试（推荐）

```bash
mvn test
```

覆盖：

- `ConcurrentRateLimitTest`：并发限流不超额、两维度拒绝原因可区分、窗口恢复；
- `VersionBoundaryTest`：未知版本/已下线/弃用、未知字段（删除改名）、
  新增字段兼容、破坏性注册 fail-fast、生命周期流转；
- `RetryDedupTest`：超时/连接失败/业务拒绝/内部错误归一化、
  读重试恢复、写超时不重试、连接失败重试一次、
  同键 20 并发写仅生效一次、同键重放复用终态；
- `GovernanceWebIntegrationTest`：完整流水线 HTTP 状态码、错误码与"细节不透出"。

测试日志中每条用例都打印**请求输入、判定依据与最终结果**。

### 6.2 手工 curl 验证

```bash
mvn spring-boot:run

# 正常请求（v2，新字段 remark）
curl -s -X POST localhost:8080/api/governance/invoke \
  -H 'X-Caller-Id: c1' -H 'X-Api-Version: v2' -H 'Content-Type: application/json' \
  -d '{"routeId":"orders","operation":"read","simulate":"ok",
       "fields":{"name":"a","amount":1,"remark":"hi"}}'

# 连续 11 次触发调用方限流（429 + CALLER_QUOTA_EXHAUSTED）
for i in $(seq 1 11); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/governance/invoke \
    -H 'X-Caller-Id: c2' -H 'X-Api-Version: v2' -H 'Content-Type: application/json' \
    -d '{"routeId":"orders","operation":"read","simulate":"ok","fields":{"name":"a"}}'
done

# 未知版本（404）/ 已下线版本（410）/ 未知字段（400）
curl -s -X POST localhost:8080/api/governance/invoke -H 'X-Caller-Id: c3' \
  -H 'X-Api-Version: v99' -H 'Content-Type: application/json' \
  -d '{"routeId":"orders","operation":"read","simulate":"ok","fields":{"name":"a"}}'

# 下游超时（504，TIMEOUT）
curl -s -X POST localhost:8080/api/governance/invoke -H 'X-Caller-Id: c4' \
  -H 'X-Api-Version: v2' -H 'Content-Type: application/json' \
  -d '{"routeId":"orders","operation":"read","simulate":"timeout","fields":{"name":"a"}}'

# 写操作幂等：同一 idempotencyKey 即使提交两次也只生效一次
for i in 1 2; do
curl -s -X POST localhost:8080/api/governance/invoke -H 'X-Caller-Id: c5' \
  -H 'X-Api-Version: v2' -H 'Content-Type: application/json' \
  -d '{"routeId":"orders","operation":"write","idempotencyKey":"k-1",
       "simulate":"ok","fields":{"name":"a","amount":1}}'
done
```
