package com.readum.domain.aiChat.dto;

/**
 * 책별 세션 목록 응답에 노출되는 합성 상태.
 * AiChatSession.Status (ACTIVE/CLOSED) 와 Summary.Status (IN_PROGRESS/COMPLETED/FAILED) 를
 * Repository JPQL CASE 식이 합성한 결과를 enum 으로 받아 컴파일 타임 안전성을 확보한다.
 */
public enum AiChatSessionDisplayStatus {
    ACTIVE,
    SUMMARIZING,
    CLOSED,
    FAILED
}
