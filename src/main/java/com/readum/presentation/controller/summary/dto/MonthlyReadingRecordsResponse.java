package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.MonthlyReadingRecordResult;

import java.util.List;

public record MonthlyReadingRecordsResponse(List<MonthlyReadingRecordResponse> records) {

    public static MonthlyReadingRecordsResponse from(List<MonthlyReadingRecordResult> results) {
        return new MonthlyReadingRecordsResponse(
                results.stream().map(MonthlyReadingRecordResponse::from).toList());
    }
}
