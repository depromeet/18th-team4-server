package com.readum.domain.exception;

public class BadRequestException extends BusinessException {

    public BadRequestException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BadRequestException(ErrorCode errorCode, String overrideMessage) {
        super(errorCode, overrideMessage);
    }
}
