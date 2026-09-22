package com.github.h3yow.apigatewaygovernancejava.resilience;

/**
 * 对下游的一次实际调用。
 */
@FunctionalInterface
public interface DownstreamCall<T> {
    T call() throws Exception;
}
