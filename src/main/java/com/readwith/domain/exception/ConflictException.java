package com.readwith.domain.exception;

public class ConflictException extends BusinessException {

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }
}
