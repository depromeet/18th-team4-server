package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.time.LocalDateTime;

public record MessageResult(
        Long id,
        AiChatMessage.Role role,
        String content,
        Integer totalTokens,
        AiChatMessage.Status status,
        LocalDateTime createdAt
) {

    public static MessageResult from(AiChatMessage entity) {
        return new MessageResult(
                entity.getId(),
                entity.getRole(),
                entity.getContent(),
                entity.getTotalTokens(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }
}
