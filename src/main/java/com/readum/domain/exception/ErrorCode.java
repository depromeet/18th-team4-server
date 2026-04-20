package com.readum.domain.exception;

public enum ErrorCode {

    // Example
    EXAMPLE_NOT_FOUND("존재하지 않는 예시입니다."),

    // Auth
    UNAUTHORIZED("인증이 필요합니다."),
    INVALID_TOKEN("유효하지 않은 토큰입니다."),
    TOKEN_EXPIRED("토큰이 만료되었습니다."),
    TOKEN_REVOKED("로그아웃된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND("리프레시 토큰이 존재하지 않습니다."),
    REFRESH_TOKEN_MISMATCH("리프레시 토큰이 일치하지 않습니다."),
    REFRESH_TOKEN_EXPIRED("리프레시 토큰이 만료되었습니다."),
    REFRESH_TOKEN_REUSE_DETECTED("리프레시 토큰 재사용이 감지되었습니다."),

    // Common
    SERVICE_UNAVAILABLE("서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
