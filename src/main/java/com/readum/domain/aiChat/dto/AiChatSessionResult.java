package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;

import java.time.LocalDateTime;

public record AiChatSessionResult(
        Long sessionId,
        String title,
        AiChatSession.Status status,
        LocalDateTime lastChattedAt
) {

    public static AiChatSessionResult from(AiChatSessionListProjection projection) {
        return new AiChatSessionResult(
                projection.sessionId(),
                projection.title(),
                projection.status(),
                projection.lastChattedAt()
        );
    }
}
