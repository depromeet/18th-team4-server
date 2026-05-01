package com.readum.domain.aiChat.exception;

import com.readum.domain.exception.ErrorCode;

public enum AiChatErrorCode implements ErrorCode {

    USER_BOOK_NOT_FOUND("해당 도서를 찾을 수 없습니다."),
    CHAT_VOLUME_NOT_ENOUGH("감상문 초안을 생성하기에 대화량이 부족합니다.");

    private final String message;

    AiChatErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
