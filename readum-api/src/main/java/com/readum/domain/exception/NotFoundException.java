package com.readum.domain.exception;

public class NotFoundException extends BusinessException {

    public NotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }
}
