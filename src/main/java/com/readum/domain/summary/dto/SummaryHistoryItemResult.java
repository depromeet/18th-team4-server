package com.readum.domain.summary.dto;

import com.readum.model.summary.repository.projection.SummaryHistoryProjection;

import java.time.LocalDateTime;

/**
 * 감상 기록 1건. 목록 카드 표시·상세 이동에 필요한 감상문 id, 책 제목, 세션 제목, 생성일을 담는다.
 * summaryId 는 감상문 행의 PK 라 항상 존재한다. sessionTitle 은 첫 대화 교환 뒤 비동기로 생성되며,
 * 그 제목 생성이 실패한 드문 경우에만 null 일 수 있다.
 */
public record SummaryHistoryItemResult(
        Long summaryId,
        String bookTitle,
        String sessionTitle,
        LocalDateTime createdAt
) {

    public static SummaryHistoryItemResult from(SummaryHistoryProjection projection) {
        return new SummaryHistoryItemResult(
                projection.summaryId(),
                projection.bookTitle(),
                projection.sessionTitle(),
                projection.createdAt()
        );
    }
}
