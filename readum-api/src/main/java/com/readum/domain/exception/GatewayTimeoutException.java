package com.readum.domain.exception;

public class GatewayTimeoutException extends ExternalApiException {

    public GatewayTimeoutException(ErrorCode errorCode) {
        super(errorCode);
    }
}
