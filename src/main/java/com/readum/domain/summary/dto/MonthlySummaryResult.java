package com.readum.domain.summary.dto;

import com.readum.model.summary.entity.Summary;

import java.time.LocalDate;

public record MonthlySummaryResult(
        Long summaryId,
        LocalDate summaryDate,
        String title,
        String body,
        String bookTitle
) {

    public static MonthlySummaryResult from(Summary summary, String bookTitle) {
        return new MonthlySummaryResult(
                summary.getId(), summary.getSummaryDate(), summary.getTitle(), summary.getBody(), bookTitle);
    }
}
