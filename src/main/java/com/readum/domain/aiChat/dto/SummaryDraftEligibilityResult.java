package com.readum.domain.aiChat.dto;

import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;

public record SummaryDraftEligibilityResult(boolean eligible, String reason, String message) {

    public static SummaryDraftEligibilityResult from(SummaryDraftEligibility eligibility) {
        if (eligibility.eligible()) {
            return new SummaryDraftEligibilityResult(true, null, null);
        }
        IneligibleReason reason = eligibility.reason();
        return new SummaryDraftEligibilityResult(false, reason.name(), reason.getMessage());
    }
}
