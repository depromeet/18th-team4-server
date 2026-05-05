package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.MessageResult;

import java.time.LocalDateTime;

public record MessageResponse(
        Long id,
        String role,
        String content,
        Integer tokenCount,
        String status,
        LocalDateTime createdAt
) {

    public static MessageResponse from(MessageResult result) {
        return new MessageResponse(
                result.id(),
                result.role().name(),
                result.content(),
                result.totalTokens(),
                result.status().name(),
                result.createdAt()
        );
    }
}
