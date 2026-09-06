package com.readum.domain.aiChat.dto;

public record AiChatSessionListCommand(
        Long userId,
        Long userBookId,
        int page,
        int size
) {
}
