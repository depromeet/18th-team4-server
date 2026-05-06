package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatSessionResult;

import java.time.LocalDateTime;

public record AiChatSessionResponse(
        Long sessionId,
        String title,
        String status,
        LocalDateTime lastChattedAt
) {

    public static AiChatSessionResponse from(AiChatSessionResult result) {
        return new AiChatSessionResponse(
                result.sessionId(),
                result.title(),
                result.status().name(),
                result.lastChattedAt()
        );
    }
}
