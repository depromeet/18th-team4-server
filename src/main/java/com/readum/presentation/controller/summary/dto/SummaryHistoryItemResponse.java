package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.SummaryHistoryItemResult;

import java.time.LocalDateTime;

public record SummaryHistoryItemResponse(
        Long summaryId,
        String bookTitle,
        String sessionTitle,
        LocalDateTime createdAt
) {

    public static SummaryHistoryItemResponse from(SummaryHistoryItemResult result) {
        return new SummaryHistoryItemResponse(
                result.summaryId(),
                result.bookTitle(),
                result.sessionTitle(),
                result.createdAt()
        );
    }
}
