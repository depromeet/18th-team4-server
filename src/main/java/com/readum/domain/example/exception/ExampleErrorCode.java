package com.readum.domain.example.exception;

import com.readum.domain.exception.ErrorCode;

public enum ExampleErrorCode implements ErrorCode {

    EXAMPLE_NOT_FOUND("존재하지 않는 예시입니다.");

    private final String message;

    ExampleErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
