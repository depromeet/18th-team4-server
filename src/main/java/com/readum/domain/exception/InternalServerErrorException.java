package com.readum.domain.exception;

public class InternalServerErrorException extends BusinessException {

    public InternalServerErrorException(ErrorCode errorCode) {
        super(errorCode);
    }
}
