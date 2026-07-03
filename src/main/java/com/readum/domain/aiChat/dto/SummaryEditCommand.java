package com.readum.domain.aiChat.dto;

public record SummaryEditCommand(
        Long userId,
        Long sessionId,
        String title,
        String body
) {
}
