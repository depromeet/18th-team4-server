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
        // 별도 summaryDate 컬럼을 두지 않으므로 "감상문 작성일" 은 생성일(createdAt) 의 날짜로 본다.
        return new MonthlySummaryResult(
                summary.getId(), summary.getCreatedAt().toLocalDate(),
                summary.getTitle(), summary.getBody(), bookTitle);
    }
}
