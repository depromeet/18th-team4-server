package com.readwith.domain.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // Example
    EXAMPLE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 예시입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
