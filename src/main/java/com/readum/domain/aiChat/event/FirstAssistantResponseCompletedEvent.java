package com.readum.domain.aiChat.event;

import com.readum.model.aiChat.entity.AiChatMessage;

/**
 * 첫 ASSISTANT 응답이 성공 저장(COMPLETED)된 직후 발행되는 도메인 이벤트.
 * AFTER_COMMIT 리스너가 이 이벤트를 받아 세션 제목을 생성한다(커밋 후 실행이라 제목 생성 실패가 메시지 저장을 롤백시키지 않음).
 *
 * @param sessionId        제목을 붙일 세션 id
 * @param firstUserMessage 제목 생성 입력이 되는 유저의 첫 COMPLETED 질문(스칼라 필드만 가져 커밋 후 접근해도 안전)
 */
public record FirstAssistantResponseCompletedEvent(Long sessionId, AiChatMessage firstUserMessage) {
}
