package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.SummaryHistoryItemResult;

import java.time.LocalDateTime;

public record SummaryHistoryItemResponse(
        String bookTitle,
        String content,
        LocalDateTime createdAt
) {

    public static SummaryHistoryItemResponse from(SummaryHistoryItemResult result) {
        return new SummaryHistoryItemResponse(
                result.bookTitle(),
                result.content(),
                result.createdAt()
        );
    }
}
