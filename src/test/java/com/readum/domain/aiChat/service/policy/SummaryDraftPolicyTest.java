package com.readum.domain.aiChat.service.policy;

import com.readum.domain.aiChat.dto.SummaryDraftEligibility;
import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryDraftPolicyTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_BOOK_ID = 1L;

    private final SummaryDraftPolicy summaryDraftPolicy = new SummaryDraftPolicy();

    @Test
    void 정상_세션이면_eligible_상태로_평가된다() {
        AiChatSession session = activeSession(SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS + 100);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isNull();
        assertThatNoException().isThrownBy(() -> summaryDraftPolicy.assertEligible(session));
    }

    @Test
    void 잠긴_세션이면_SESSION_ALREADY_CLOSED_사유로_evaluate된다() {
        AiChatSession session = lockedSession(SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS + 100);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.SESSION_ALREADY_CLOSED);
    }

    @Test
    void 잠긴_세션에_assertEligible하면_ConflictException이_발생한다() {
        AiChatSession session = lockedSession(SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS + 100);

        assertThatThrownBy(() -> summaryDraftPolicy.assertEligible(session))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_ALREADY_CLOSED);
    }

    @Test
    void 누적_토큰이_부족하면_CHAT_VOLUME_NOT_ENOUGH_사유로_evaluate된다() {
        AiChatSession session = activeSession(SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS - 1);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH);
    }

    @Test
    void 누적_토큰이_부족할때_assertEligible하면_UnprocessableEntityException이_발생한다() {
        AiChatSession session = activeSession(SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS - 1);

        assertThatThrownBy(() -> summaryDraftPolicy.assertEligible(session))
                .asInstanceOf(InstanceOfAssertFactories.type(UnprocessableEntityException.class))
                .extracting(UnprocessableEntityException::getErrorCode)
                .isEqualTo(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
    }

    @Test
    void 임계값과_같으면_eligible로_평가된다() {
        AiChatSession session = activeSession(SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void 잠김_상태가_토큰_부족보다_먼저_평가된다() {
        AiChatSession session = lockedSession(SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS - 1);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.reason()).isEqualTo(IneligibleReason.SESSION_ALREADY_CLOSED);
    }

    private AiChatSession activeSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedActiveSession(
                SESSION_ID, USER_BOOK_ID, 0, accumulatedTokens, null
        );
    }

    private AiChatSession lockedSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedLockedSession(
                SESSION_ID, USER_BOOK_ID, 0, accumulatedTokens, null
        );
    }
}
