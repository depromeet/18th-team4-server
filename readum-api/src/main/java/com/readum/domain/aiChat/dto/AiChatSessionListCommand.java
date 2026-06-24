package com.readum.domain.aiChat.dto;

public record AiChatSessionListCommand(
        String userSessionId,
        Long userBookId,
        int page,
        int size
) {
}
