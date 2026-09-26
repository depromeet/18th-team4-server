package com.readum.model.aiChat.repository.projection;

import java.time.LocalDateTime;

/**
 * 책별 채팅 세션 목록 조회용 projection.
 * status 는 AiChatSession.status 와 세션의 최신 Summary.status 를 합성해 JPQL CASE 식이 도출한 문자열 — "ACTIVE" / "SUMMARIZING" / "SUMMARIZED" / "FAILED".
 */
public record AiChatSessionListProjection(
        Long sessionId,
        String title,
        String status,
        LocalDateTime lastChattedAt
) {
}
