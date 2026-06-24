package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.SummaryDraftEligibilityResult;
import jakarta.annotation.Nullable;

public record SummaryDraftEligibilityResponse(
        boolean eligible,
        @Nullable String reason,
        @Nullable String message,
        int progressPercent
) {

    public static SummaryDraftEligibilityResponse from(SummaryDraftEligibilityResult result) {
        return new SummaryDraftEligibilityResponse(
                result.eligible(), result.reason(), result.message(), result.progressPercent());
    }
}
