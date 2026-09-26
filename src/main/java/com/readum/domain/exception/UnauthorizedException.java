package com.readum.domain.exception;

public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }
}
