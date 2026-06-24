package com.readum.domain.aiChat.dto;

public record MessageListCommand(
        String userSessionId,
        Long sessionId,
        int page,
        int size
) {
}
