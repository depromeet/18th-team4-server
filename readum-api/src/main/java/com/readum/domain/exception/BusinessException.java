package com.readum.domain.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 사용자에게 노출할 메시지를 ErrorCode 기본 메시지 대신 명시적으로 지정한다.
     * 거부 응답 텍스트의 정본을 properties 한 곳에 두기 위해(예: 가드레일 failureResponse) 사용한다.
     */
    public BusinessException(ErrorCode errorCode, String overrideMessage) {
        super(overrideMessage);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
