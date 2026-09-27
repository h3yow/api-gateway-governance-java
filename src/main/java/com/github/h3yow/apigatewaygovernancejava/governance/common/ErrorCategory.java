package com.github.h3yow.apigatewaygovernancejava.governance.common;

/**
 * 对外错误的稳定三分类，回答调用方最关心的问题：“我能不能改？要不要重试？”
 *
 * <ul>
 *   <li>{@link #RATE_LIMITED}：被限流。请求本身合法，暂时无额度；调用方应按响应给出的
 *       {@code retryAfterSeconds} 等待后重试，不需要修改请求内容。</li>
 *   <li>{@link #CALLER_FIXABLE}：调用方可自行修正的问题（缺必要信息、参数非法/越界、
 *       版本或字段不符合约定、写操作缺幂等键、同键在途冲突、业务被拒）。
 *       结论稳定归这一类，message 明确说明要改什么；不要重试原请求，更不是服务端故障。</li>
 *   <li>{@link #SERVER_FAULT}：服务端自身故障（超时、连接失败、未知内部错误）。
 *       不应由调用方通过“改请求”解决；传输类故障由治理层在内部安全重试，
 *       对外返回时重试已耗尽，调用方不要盲目重试。</li>
 * </ul>
 */
public enum ErrorCategory {

    RATE_LIMITED("rate_limited", "被限流：请按 retryAfterSeconds 等待后重试，无需修改请求"),
    CALLER_FIXABLE("caller_fixable", "调用方需要修正：请按 message 修改请求后再发，不要原样重试"),
    SERVER_FAULT("server_fault", "服务端故障：内部已按安全策略处理，请勿盲目重试，可联系服务方");

    private final String wire;
    private final String guidance;

    ErrorCategory(String wire, String guidance) {
        this.wire = wire;
        this.guidance = guidance;
    }

    /** 对外稳定的机器可读分类串。 */
    public String wire() {
        return wire;
    }

    /** 面向调用方的处理指引。 */
    public String guidance() {
        return guidance;
    }
}
