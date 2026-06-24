package com.readum.domain.aiChat.dto;

/**
 * 책별 세션 목록 응답에 노출되는 합성 상태.
 * AiChatSession.Status (ACTIVE/LOCKED) 와 감상문 존재 여부를 Repository JPQL CASE 식이 합성한 결과를
 * enum 으로 받아 컴파일 타임 안전성을 확보한다.
 *  - SUMMARIZING: 감상문 생성 중 (세션 잠김 — 메시지 전송 불가)
 *  - ACTIVE: 대화 가능, 생성된 감상문 없음
 *  - SUMMARIZED: 대화 가능, 감상문 존재 (감상문은 성공 기록만 남으므로 존재 여부만 본다)
 */
public enum AiChatSessionDisplayStatus {
    ACTIVE,
    SUMMARIZING,
    SUMMARIZED
}
