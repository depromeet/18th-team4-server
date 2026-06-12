package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.SummaryResult;

public record SummaryDetailResponse(
        String title,
        String body
) {

    public static SummaryDetailResponse from(SummaryResult result) {
        return new SummaryDetailResponse(result.title(), result.body());
    }
}
