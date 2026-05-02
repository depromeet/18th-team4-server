package com.readum.domain.aiChat.exception;

import com.readum.domain.exception.ErrorCode;

public enum AiChatErrorCode implements ErrorCode {

    USER_BOOK_NOT_FOUND("해당 도서를 찾을 수 없습니다."),
    SESSION_NOT_FOUND("세션을 찾을 수 없습니다."),
    SESSION_CLOSED("종료된 세션에는 메시지를 보낼 수 없습니다."),
    MESSAGE_CONTENT_BLANK("메시지 본문은 비어 있을 수 없습니다."),
    MESSAGE_CONTENT_TOO_LONG("메시지 본문은 1000자 이하여야 합니다."),
    AI_RATE_LIMIT_EXCEEDED("AI 호출 한도를 초과했습니다."),
    AI_PROVIDER_ERROR("AI 응답 처리에 실패했습니다."),
    AI_PROVIDER_TRANSIENT("일시적인 AI 응답 오류입니다. 잠시 후 다시 시도해 주세요."),
    AI_STREAM_INTERRUPTED("AI 응답이 중단되었습니다.");

    private final String message;

    AiChatErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
