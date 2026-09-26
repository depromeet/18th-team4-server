package com.readum.domain.summary.dto;

import com.readum.model.aiChat.entity.AiChatSession;

import java.time.LocalDateTime;

/**
 * 홈 캘린더의 독서 기록 항목 — 채팅 세션 단위.
 * 감상문은 선택적이라 {@code summaryId} 는 감상문이 생성된 세션만 값을 가진다(없으면 null).
 */
public record MonthlyReadingRecordResult(
        Long chatSessionId,
        Long summaryId,
        String bookTitle,
        String chatSummary,
        LocalDateTime lastChattedAt
) {

    public static MonthlyReadingRecordResult from(
            AiChatSession session, String bookTitle, Long summaryId, LocalDateTime lastChattedAt) {
        // chatSummary = 자동 생성된 채팅 제목(AiChatSession.title). 제목 생성 전이면 null 일 수 있다.
        return new MonthlyReadingRecordResult(
                session.getId(), summaryId, bookTitle, session.getTitle(), lastChattedAt);
    }
}
