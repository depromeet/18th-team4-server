package com.readwith.domain.exception;

public enum ErrorCode {

    // Example
    EXAMPLE_NOT_FOUND("존재하지 않는 예시입니다.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
