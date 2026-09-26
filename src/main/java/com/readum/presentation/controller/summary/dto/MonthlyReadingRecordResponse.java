package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.MonthlyReadingRecordResult;

import java.time.LocalDateTime;

public record MonthlyReadingRecordResponse(
        Long chatSessionId,
        Long summaryId,
        String bookTitle,
        String chatSummary,
        LocalDateTime lastChattedAt
) {

    public static MonthlyReadingRecordResponse from(MonthlyReadingRecordResult result) {
        return new MonthlyReadingRecordResponse(
                result.chatSessionId(), result.summaryId(), result.bookTitle(),
                result.chatSummary(), result.lastChattedAt());
    }
}
