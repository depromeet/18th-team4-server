package com.readum.domain.summary.dto;

import com.readum.model.summary.repository.projection.SummaryHistoryProjection;

import java.time.LocalDateTime;

/**
 * 감상 기록 1건. "감상문 내용"의 정의 자체가 미리보기이므로,
 * 본문을 100자까지만 노출하고 초과분은 "..." 로 줄이는 책임을 이 Result 가 갖는다.
 */
public record SummaryHistoryItemResult(
        String bookTitle,
        String content,
        LocalDateTime createdAt
) {

    private static final int MAX_CONTENT_PREVIEW_LENGTH = 100;

    public static SummaryHistoryItemResult from(SummaryHistoryProjection projection) {
        return new SummaryHistoryItemResult(
                projection.bookTitle(),
                preview(projection.body()),
                projection.createdAt()
        );
    }

    private static String preview(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= MAX_CONTENT_PREVIEW_LENGTH
                ? body
                : body.substring(0, MAX_CONTENT_PREVIEW_LENGTH) + "...";
    }
}
