package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

public record GenerateSessionTitleCommand(
        Long sessionId,
        List<AiChatMessage> messages
) {
}
