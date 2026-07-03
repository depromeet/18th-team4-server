package com.readum.domain.aiChat.service.policy;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.SummaryDraftEligibility;
import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SummaryDraftPolicy {

    private final AiChatProperties aiChatProperties;

    public SummaryDraftEligibility evaluate(AiChatSession session) {
        return switch (session.getStatus()) {
            case LOCKED -> SummaryDraftEligibility.fail(IneligibleReason.ALREADY_SUMMARIZED);
            case ACTIVE -> session.getAccumulatedTokens() < minAccumulatedTokens()
                    ? SummaryDraftEligibility.fail(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH)
                    : SummaryDraftEligibility.pass();
        };
    }

    public void assertEligible(AiChatSession session) {
        SummaryDraftEligibility eligibility = evaluate(session);
        if (eligibility.eligible()) {
            return;
        }
        throw switch (eligibility.reason()) {
            case ALREADY_SUMMARIZED -> new ConflictException(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED);
            case SUMMARY_IN_PROGRESS -> new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
            case CHAT_VOLUME_NOT_ENOUGH -> new UnprocessableEntityException(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
        };
    }

    public boolean isEligible(int accumulatedTokensSinceLastSummary) {
        return accumulatedTokensSinceLastSummary >= minAccumulatedTokens();
    }

    public int calculateProgressPercent(int accumulatedTokens) {
        return Math.min(accumulatedTokens * 100 / minAccumulatedTokens(), 100);
    }

    public int minAccumulatedTokens() {
        return aiChatProperties.summaryDraft().minAccumulatedTokens();
    }
}
