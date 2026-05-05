package com.readum.domain.aiChat.service.policy;

import com.readum.domain.aiChat.dto.SummaryDraftEligibility;
import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatSession;
import org.springframework.stereotype.Component;

@Component
public class SummaryDraftPolicy {

    // TODO: 정책 확정 후 상수값 조정 필요
    public static final int MIN_ACCUMULATED_TOKENS = 500;

    public SummaryDraftEligibility evaluate(AiChatSession session) {
        if (session.getStatus() == AiChatSession.Status.CLOSED) {
            return SummaryDraftEligibility.fail(IneligibleReason.SESSION_ALREADY_CLOSED);
        }
        if (session.getAccumulatedTokens() < MIN_ACCUMULATED_TOKENS) {
            return SummaryDraftEligibility.fail(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH);
        }
        return SummaryDraftEligibility.pass();
    }

    public void assertEligible(AiChatSession session) {
        SummaryDraftEligibility eligibility = evaluate(session);
        if (eligibility.eligible()) {
            return;
        }
        switch (eligibility.reason()) {
            case SESSION_ALREADY_CLOSED ->
                    throw new ConflictException(AiChatErrorCode.SESSION_ALREADY_CLOSED);
            case CHAT_VOLUME_NOT_ENOUGH ->
                    throw new UnprocessableEntityException(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
        }
    }
}
