package com.readum.domain.aiChat.dto;

import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;

public record SummaryDraftEligibilityResult(
        boolean eligible,
        String reason,
        String message,
        int progressPercent
) {

    public static SummaryDraftEligibilityResult from(
            SummaryDraftEligibility eligibility, int progressPercent
    ) {
        if (eligibility.eligible()) {
            return new SummaryDraftEligibilityResult(true, null, null, progressPercent);
        }
        IneligibleReason reason = eligibility.reason();
        return new SummaryDraftEligibilityResult(
                false, reason.name(), reason.getMessage(), progressPercent);
    }
}
