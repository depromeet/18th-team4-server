package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import jakarta.annotation.Nullable;

public record SummaryDraftResponse(
        String title,
        String body,
        @Nullable String quote
) {

    public static SummaryDraftResponse from(SummaryDraftResult result) {
        return new SummaryDraftResponse(result.title(), result.body(), result.quote());
    }
}

