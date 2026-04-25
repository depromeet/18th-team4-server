package com.readum.domain.userbook.exception;

import com.readum.domain.exception.ErrorCode;

public enum UserBookErrorCode implements ErrorCode {

    ALREADY_EXISTS("이미 책장에 등록된 도서입니다.");

    private final String message;

    UserBookErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
