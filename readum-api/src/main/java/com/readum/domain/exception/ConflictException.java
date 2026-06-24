package com.readum.domain.exception;

public class ConflictException extends BusinessException {

    private final Object payload;

    public ConflictException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public ConflictException(ErrorCode errorCode, Object payload) {
        super(errorCode);
        this.payload = payload;
    }

    public Object getPayload() {
        return payload;
    }
}
