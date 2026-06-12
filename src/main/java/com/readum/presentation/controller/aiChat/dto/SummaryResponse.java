package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SummaryResult;

public record SummaryResponse(
        String title,
        String body
) {

    public static SummaryResponse from(SummaryResult result) {
        return new SummaryResponse(result.title(), result.body());
    }
}
