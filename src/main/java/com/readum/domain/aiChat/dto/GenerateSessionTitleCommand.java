package com.readum.domain.aiChat.dto;

public record GenerateSessionTitleCommand(
        Long sessionId,
        String firstUserMessage
) {
}
