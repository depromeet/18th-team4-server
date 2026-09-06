package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

public record HistoryMessage(Role role, String content) {

    public enum Role {
        USER, ASSISTANT
    }

    /**
     * AiChatMessage 엔티티를 LLM 입력용 HistoryMessage 로 변환한다.
     */
    public static HistoryMessage from(AiChatMessage entity) {
        Role mappedRole = switch (entity.getRole()) {
            case USER -> Role.USER;
            case ASSISTANT -> Role.ASSISTANT;
        };
        return new HistoryMessage(mappedRole, entity.getContent());
    }
}
