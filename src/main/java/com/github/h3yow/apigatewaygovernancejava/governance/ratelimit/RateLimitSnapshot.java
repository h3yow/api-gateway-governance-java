package com.github.h3yow.apigatewaygovernancejava.governance.ratelimit;

import java.util.Map;

/**
 * 限流运行期只读快照：供运维/调用方查询当前各路由、各调用方维度的配额消耗。
 *
 * <p>一致性承诺：
 * <ul>
 *   <li>每个维度的快照取自单个不可变对象，窗口视图、桶视图与累计计数在同一次读取中呈现，
 *       不会读到更新到一半的撕裂状态；</li>
 *   <li>窗口 used 绝不超过硬上限，桶可用令牌绝不超过容量、不为负；</li>
 *   <li>查询是只读的：不触发任何 CAS / 窗口翻转 / 令牌填充落盘，不占用、不改变任何计数，
 *       也不会因查询而创建新的限流器。</li>
 * </ul>
 */
public record RateLimitSnapshot(long capturedAtMillis,
                                Map<String, ScopeSnapshot> routes,
                                Map<String, ScopeSnapshot> callers) {
}
