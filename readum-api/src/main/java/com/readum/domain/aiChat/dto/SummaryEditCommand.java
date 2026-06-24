package com.readum.domain.aiChat.dto;

public record SummaryEditCommand(
        String userSessionId,
        Long sessionId,
        String title,
        String body
) {
}
