package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;

public record AiChatSessionCreateResponse(Long sessionId) {

    public static AiChatSessionCreateResponse from(AiChatSessionCreateResult result) {
        return new AiChatSessionCreateResponse(result.id());
    }
}
