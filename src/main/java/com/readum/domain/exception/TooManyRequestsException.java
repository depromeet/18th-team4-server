package com.readum.domain.exception;

public class TooManyRequestsException extends BusinessException {

    public TooManyRequestsException(ErrorCode errorCode) {
        super(errorCode);
    }
}
