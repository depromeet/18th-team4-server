package com.readum.domain.aiChat.dto;

import com.readum.domain.exception.RateLimitInfo;

import java.time.LocalDateTime;

public sealed interface MessageStreamEvent
        permits MessageStreamEvent.Token, MessageStreamEvent.Done, MessageStreamEvent.Error {

    record Token(String delta) implements MessageStreamEvent {}

    // messageId 는 클라이언트가 사용하지 않아 payload 에서 제외한다(필요해지면 이력 조회로 받는다).
    record Done(
            TokenCount tokenCount,
            LocalDateTime createdAt
    ) implements MessageStreamEvent {
    }

    // SSE 응답이 이미 commit 된 상태(text/event-stream) 에서 발생한 에러는 HTTP 헤더로 메타정보를
    // 전달할 수 없으므로, 429 의 X-RateLimit-* 정보는 이 payload 의 rateLimitInfo 로 운반한다.
    // 429 가 아닌 일반 에러에서는 null.
    record Error(String code, String message, RateLimitInfo rateLimitInfo) implements MessageStreamEvent {

        public static Error of(String code, String message) {
            return new Error(code, message, null);
        }
    }

    record TokenCount(Integer input, Integer output, Integer total) {
    }
}
