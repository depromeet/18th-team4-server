package com.readum.presentation.controller.summary.dto;

import com.readum.domain.summary.dto.SummaryResult;

public record SummaryDetailResponse(Summary summary) {

    public record Summary(
            Long aiChatSessionId,
            String title,
            String body
    ) {}

    public static SummaryDetailResponse from(SummaryResult result) {
        return new SummaryDetailResponse(new Summary(result.aiChatSessionId(), result.title(), result.body()));
    }
}
