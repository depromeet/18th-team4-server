package com.readum.domain.exception;

public class BadGatewayException extends ExternalApiException {

    public BadGatewayException(ErrorCode errorCode) {
        super(errorCode);
    }
}
