package com.readum.domain.aiChat.dto;

public record SendMessageCommand(
        String userSessionId,
        Long sessionId,
        String content
) {
}
