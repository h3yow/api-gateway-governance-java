package com.github.h3yow.apigatewaygovernancejava.resilience;

/**
 * 下游业务拒绝（如余额不足、参数业务校验失败）。
 * 结果确定，不进行重试。
 */
public class BusinessRejectionException extends DownstreamException {

    public BusinessRejectionException(String safeMessage) {
        super(ErrorType.BUSINESS_REJECTION, safeMessage, null);
    }
}
