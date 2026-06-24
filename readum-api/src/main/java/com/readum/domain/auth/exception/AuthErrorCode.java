package com.readum.domain.auth.exception;

import com.readum.domain.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {

    UNAUTHORIZED("인증이 필요합니다."),
    FORBIDDEN("접근 권한이 없습니다."),
    INVALID_TOKEN("유효하지 않은 토큰입니다."),
    TOKEN_EXPIRED("토큰이 만료되었습니다."),
    TOKEN_REVOKED("로그아웃된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND("리프레시 토큰이 존재하지 않습니다."),
    REFRESH_TOKEN_EXPIRED("리프레시 토큰이 만료되었습니다."),
    REFRESH_TOKEN_REUSE_DETECTED("리프레시 토큰 재사용이 감지되었습니다.");

    private final String message;

    AuthErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
