package com.readum.domain.exception;

public class ExternalApiException extends BusinessException {

    public ExternalApiException(ErrorCode errorCode) {
        super(errorCode);
    }
}
