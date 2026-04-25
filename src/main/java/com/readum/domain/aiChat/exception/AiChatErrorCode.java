package com.readum.domain.aiChat.exception;

import com.readum.domain.exception.ErrorCode;

public enum AiChatErrorCode implements ErrorCode {

    USER_BOOK_NOT_FOUND("해당 도서를 찾을 수 없습니다.");

    private final String message;

    AiChatErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
