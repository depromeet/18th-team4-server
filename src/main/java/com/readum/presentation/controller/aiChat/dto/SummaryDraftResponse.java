package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SummaryDraftResult;

public record SummaryDraftResponse(
        String title,
        String body
) {

    public static SummaryDraftResponse from(SummaryDraftResult result) {
        return new SummaryDraftResponse(result.title(), result.body());
    }
}

