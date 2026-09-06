package com.readum.domain.user.exception;

import com.readum.domain.exception.ErrorCode;

public enum UserErrorCode implements ErrorCode {

    INVALID_SESSION("유효하지 않은 세션입니다."),
    INVALID_NICKNAME("닉네임은 영문 대/소문자, 한글, 숫자로 구성된 1~10자여야 합니다.");

    private final String message;

    UserErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
