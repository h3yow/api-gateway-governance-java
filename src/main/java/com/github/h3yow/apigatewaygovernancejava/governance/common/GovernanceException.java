package com.github.h3yow.apigatewaygovernancejava.governance.common;

/** 治理层产生的、可对调用方解释的确定性异常。 */
public class GovernanceException extends RuntimeException {

    private final ErrorCode errorCode;

    public GovernanceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
