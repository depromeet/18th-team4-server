package com.readum.domain.exception;

public class UnprocessableEntityException extends BusinessException {

    public UnprocessableEntityException(ErrorCode errorCode) {
        super(errorCode);
    }
}
