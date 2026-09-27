package com.github.h3yow.apigatewaygovernancejava.governance.common;

/**
 * 对外结论的一级分类，用于让调用方一眼分辨「该自己改」还是「服务端问题」，
 * 三类语义稳定、互不混淆：
 * <ul>
 *   <li>{@link #RATE_LIMITED}：被限流，请求本身合法，按 Retry-After 等待后可原样重试；</li>
 *   <li>{@link #CLIENT_ERROR}：调用方自己就能改好的问题（缺信息、取值非法、版本/字段不符、
 *       写操作缺幂等键、同键并发重复提交等），必须修改请求后再提交，不应原样重试；</li>
 *   <li>{@link #SERVER_ERROR}：服务端/下游自身故障（超时、连接失败、内部错误），
 *       调用方无法通过修改请求解决，也不应被引导去排查服务端。</li>
 * </ul>
 */
public enum ErrorCategory {

    RATE_LIMITED,
    CLIENT_ERROR,
    SERVER_ERROR;

    /** 对外稳定的机器可读串（小写下划线），与 errorCode 同风格。 */
    public String wireName() {
        return switch (this) {
            case RATE_LIMITED -> "rate_limited";
            case CLIENT_ERROR -> "client_error";
            case SERVER_ERROR -> "server_error";
        };
    }
}
