package com.readum.domain.exception;

public class ServiceUnavailableException extends BusinessException {

    public ServiceUnavailableException(ErrorCode errorCode) {
        super(errorCode);
    }
}
