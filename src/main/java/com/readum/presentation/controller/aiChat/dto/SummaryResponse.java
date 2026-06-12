package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.summary.dto.SummaryResult;
import jakarta.annotation.Nullable;

public record SummaryResponse(
        String title,
        String body,
        @Nullable String quote
) {

    public static SummaryResponse from(SummaryResult result) {
        return new SummaryResponse(result.title(), result.body(), result.quote());
    }
}
