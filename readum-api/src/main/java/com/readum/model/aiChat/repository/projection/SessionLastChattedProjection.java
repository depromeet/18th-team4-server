package com.readum.model.aiChat.repository.projection;

import java.time.LocalDateTime;

/**
 * 세션별 마지막 채팅 시각 집계 결과 — 책별 세션 목록의 "마지막 대화일" 합성용.
 * AiChatMessage 의 자기 데이터(sessionId + createdAt 집계)만 담는다.
 */
public record SessionLastChattedProjection(
        Long sessionId,
        LocalDateTime lastChattedAt
) {
}
