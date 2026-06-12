package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.MonthlySummaryResult;

import java.util.List;

public record MonthlySummariesResponse(List<MonthlySummaryResponse> summaries) {

    public static MonthlySummariesResponse from(List<MonthlySummaryResult> results) {
        return new MonthlySummariesResponse(results.stream().map(MonthlySummaryResponse::from).toList());
    }
}
