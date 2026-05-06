package com.readum.model.aiChat.repository.projection;

import com.readum.model.aiChat.entity.AiChatSession;

import java.time.LocalDateTime;

public record AiChatSessionListProjection(
        Long sessionId,
        String title,
        AiChatSession.Status status,
        LocalDateTime lastChattedAt
) {
}
