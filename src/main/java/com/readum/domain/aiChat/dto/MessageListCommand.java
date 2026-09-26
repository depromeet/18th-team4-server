package com.readum.domain.aiChat.dto;

public record MessageListCommand(
        Long userId,
        Long sessionId,
        int page,
        int size
) {
}
