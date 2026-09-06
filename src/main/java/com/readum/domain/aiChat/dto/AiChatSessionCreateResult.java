package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatSession;

public record AiChatSessionCreateResult(Long id) {

    public static AiChatSessionCreateResult from(AiChatSession session) {
        return new AiChatSessionCreateResult(session.getId());
    }
}
