package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.MonthlySummaryResult;

import java.time.LocalDate;

public record MonthlySummaryResponse(
        Long summaryId,
        LocalDate summaryDate,
        String title,
        String body,
        String bookTitle
) {

    public static MonthlySummaryResponse from(MonthlySummaryResult result) {
        return new MonthlySummaryResponse(
                result.summaryId(), result.summaryDate(), result.title(), result.body(), result.bookTitle());
    }
}
