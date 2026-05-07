package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;

import java.time.LocalDate;

public record AiChatSessionResult(
        Long sessionId,
        String title,
        String status,
        LocalDate lastChattedDate
) {

    public static AiChatSessionResult from(AiChatSessionListProjection projection) {
        return new AiChatSessionResult(
                projection.sessionId(),
                projection.title(),
                projection.status(),
                projection.lastChattedAt() == null ? null : projection.lastChattedAt().toLocalDate()
        );
    }
}
