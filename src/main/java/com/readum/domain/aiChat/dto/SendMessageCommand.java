package com.readum.domain.aiChat.dto;

public record SendMessageCommand(
        Long userId,
        Long sessionId,
        String content
) {
}
