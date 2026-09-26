package com.readum.domain.aiChat.dto;

public record AiChatSessionCreateCommand(
        Long userId,
        Long userBookId
) {
}
