package com.readum.domain.aiChat.exception;

import com.readum.domain.exception.ErrorCode;

public enum AiChatErrorCode implements ErrorCode {

    USER_BOOK_NOT_FOUND("등록된 도서가 아닙니다."),
    SESSION_NOT_FOUND("세션을 찾을 수 없습니다."),
    SESSION_LOCKED("감상문 생성 중에는 메시지를 보낼 수 없습니다."),
    CHAT_VOLUME_NOT_ENOUGH("감상문 초안을 생성하기에 대화량이 부족합니다."),
    MESSAGE_CONTENT_BLANK("메시지 본문은 비어 있을 수 없습니다."),
    MESSAGE_CONTENT_TOO_LONG("메시지 본문은 4000자 이하여야 합니다."),
    USER_RATE_LIMIT_EXCEEDED("메시지를 너무 자주 보내고 있습니다. 잠시 후 다시 시도해 주세요."),
    GUARDRAIL_BLOCKED_INPUT("요청을 처리할 수 없습니다. 독서와 관련된 질문으로 다시 요청해 주세요."),
    GUARDRAIL_MODERATION_UNAVAILABLE("일시적으로 메시지를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    AI_RATE_LIMIT_BURST("AI 호출이 일시적으로 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."),
    AI_QUOTA_EXHAUSTED("AI 사용 한도가 소진되었습니다. 운영자에게 문의해 주세요."),
    AI_PROVIDER_ERROR("AI 응답 처리에 실패했습니다."),
    AI_PROVIDER_TRANSIENT("일시적인 AI 응답 오류입니다. 잠시 후 다시 시도해 주세요."),
    AI_STREAM_INTERRUPTED("AI 응답이 중단되었습니다."),
    SUMMARY_NOT_FOUND("아직 생성된 감상문이 없습니다."),
    SUMMARY_IN_PROGRESS("감상문을 생성 중입니다. 잠시 후 다시 시도해 주세요."),
    SESSION_ALREADY_SUMMARIZED("이미 감상문이 생성되어 종료된 세션입니다.");

    private final String message;

    AiChatErrorCode(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
