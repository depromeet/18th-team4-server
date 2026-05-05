package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

public record HistoryMessage(Role role, String content) {

    public enum Role {
        USER, ASSISTANT
    }

    /**
     * AiChatMessage 엔티티를 LLM 입력용 HistoryMessage 로 변환한다.
     * SYSTEM role 에서 예외가 발생하면 호출 경로가 깨졌다는 신호 — 정상 흐름에서는
     * AiChatMessageRepository.findRecentForContextWindow 가 USER/ASSISTANT 만 조회하므로
     * SYSTEM 이 여기 도달할 일이 없다. 이 가드는 도메인 규칙이 아니라 "이 정적 팩토리의
     * 사전 조건 위반" 을 즉시 드러내기 위한 assertion 이다.
     */
    public static HistoryMessage from(AiChatMessage entity) {
        Role mappedRole = switch (entity.getRole()) {
            case USER -> Role.USER;
            case ASSISTANT -> Role.ASSISTANT;
            case SYSTEM -> throw new IllegalStateException(
                    "SYSTEM 메시지는 HistoryMessage 로 변환할 수 없습니다. 호출자가 필터링해야 합니다."
            );
        };
        return new HistoryMessage(mappedRole, entity.getContent());
    }
}
