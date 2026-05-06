package com.readum.domain.aiChat.dto;

public record AiChatSessionCreateCommand(
        String userSessionId,
        Long userBookId
) {
}
