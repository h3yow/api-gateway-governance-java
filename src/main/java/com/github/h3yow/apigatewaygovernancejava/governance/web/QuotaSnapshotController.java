package com.github.h3yow.apigatewaygovernancejava.governance.web;

import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitService;
import com.github.h3yow.apigatewaygovernancejava.governance.ratelimit.RateLimitSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读运行期配额快照：
 *
 * <ul>
 *   <li>{@code GET /api/govern/quotas}：返回当前所有已出现过的「服务路由」与「调用方」
 *       维度的固定窗口用量、令牌桶突发余量，以及累计放行/拒绝计数。</li>
 * </ul>
 *
 * 该端点只读：不占用配额、不改变任何计数、不创建新维度状态；
 * 每个维度的字段取自同一不可变快照，并发下不会读到更新到一半的状态。
 */
@RestController
@RequestMapping("/api/govern")
public class QuotaSnapshotController {

    private final RateLimitService rateLimitService;

    public QuotaSnapshotController(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/quotas")
    public RateLimitSnapshot quotas() {
        return rateLimitService.snapshot();
    }
}
