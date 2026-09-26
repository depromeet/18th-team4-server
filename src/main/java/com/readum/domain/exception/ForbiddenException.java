package com.readum.domain.exception;

public class ForbiddenException extends BusinessException {

    public ForbiddenException(ErrorCode errorCode) {
        super(errorCode);
    }
}
