package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatSessionResult;

import java.time.LocalDate;

public record AiChatSessionResponse(
        Long sessionId,
        String title,
        String status,
        LocalDate lastChattedAt
) {

    public static AiChatSessionResponse from(AiChatSessionResult result) {
        return new AiChatSessionResponse(
                result.sessionId(),
                result.title(),
                result.status(),
                result.lastChattedAt() == null ? null : result.lastChattedAt().toLocalDate()
        );
    }
}
