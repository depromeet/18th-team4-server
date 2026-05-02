package com.readum.domain.aiChat.dto;

import java.time.LocalDateTime;

public sealed interface MessageStreamEvent
        permits MessageStreamEvent.Token, MessageStreamEvent.Done, MessageStreamEvent.Error {

    record Token(String delta) implements MessageStreamEvent {}

    record Done(
            Long messageId,
            TokenCount tokenCount,
            LocalDateTime createdAt
    ) implements MessageStreamEvent {
    }

    record Error(String code, String message) implements MessageStreamEvent {}

    record TokenCount(Integer input, Integer output, Integer total) {
    }
}
