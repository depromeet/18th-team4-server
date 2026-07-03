package com.readum.domain.aiChat.service.policy;

import com.readum.domain.aiChat.config.AiChatProperties;
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

    private SummaryDraftPolicy policyWithThreshold(int minAccumulatedTokens) {
        return new SummaryDraftPolicy(new AiChatProperties(
                null, null, null, null,
                new AiChatProperties.SummaryDraft(minAccumulatedTokens)));
    }

    private AiChatSession activeSessionWithTokens(int accumulatedTokens) {
        AiChatSession session = AiChatSession.create(1L);
        session.addAssistantTokens(accumulatedTokens);
        return session;
    }

    @Test
    void 정상_세션이면_eligible_상태로_평가된다() {
        SummaryDraftPolicy summaryDraftPolicy = policyWithThreshold(500);
        AiChatSession session = activeSession(600);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isNull();
        assertThatNoException().isThrownBy(() -> summaryDraftPolicy.assertEligible(session));
    }

    @Test
    void 잠긴_세션이면_ALREADY_SUMMARIZED_사유로_evaluate된다() {
        SummaryDraftPolicy summaryDraftPolicy = policyWithThreshold(500);
        AiChatSession session = lockedSession(600);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.ALREADY_SUMMARIZED);
    }

    @Test
    void 종료된_세션은_ALREADY_SUMMARIZED로_막는다() {
        SummaryDraftPolicy summaryDraftPolicy = policyWithThreshold(500);
        AiChatSession session = AiChatSessionFixture.persistedSummarizedSession(1L, 5L, 2, 600, "제목");

        assertThatThrownBy(() -> summaryDraftPolicy.assertEligible(session))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED);
    }

    @Test
    void 누적_토큰이_부족하면_CHAT_VOLUME_NOT_ENOUGH_사유로_evaluate된다() {
        SummaryDraftPolicy summaryDraftPolicy = policyWithThreshold(500);
        AiChatSession session = activeSession(499);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH);
    }

    @Test
    void 누적_토큰이_부족할때_assertEligible하면_UnprocessableEntityException이_발생한다() {
        SummaryDraftPolicy summaryDraftPolicy = policyWithThreshold(500);
        AiChatSession session = activeSession(499);

        assertThatThrownBy(() -> summaryDraftPolicy.assertEligible(session))
                .asInstanceOf(InstanceOfAssertFactories.type(UnprocessableEntityException.class))
                .extracting(UnprocessableEntityException::getErrorCode)
                .isEqualTo(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);
    }

    @Test
    void 임계값과_같으면_eligible로_평가된다() {
        SummaryDraftPolicy summaryDraftPolicy = policyWithThreshold(500);
        AiChatSession session = activeSession(500);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void 잠김_상태가_토큰_부족보다_먼저_평가된다() {
        SummaryDraftPolicy summaryDraftPolicy = policyWithThreshold(500);
        AiChatSession session = lockedSession(499);

        SummaryDraftEligibility result = summaryDraftPolicy.evaluate(session);

        assertThat(result.reason()).isEqualTo(IneligibleReason.ALREADY_SUMMARIZED);
    }

    // --- 경계값 테스트 ---

    @Test
    void 누적_토큰이_문턱값_미만이면_생성_불가_사유는_CHAT_VOLUME_NOT_ENOUGH_다() {
        SummaryDraftEligibility eligibility =
                policyWithThreshold(500).evaluate(activeSessionWithTokens(499));

        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.reason()).isEqualTo(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH);
    }

    @Test
    void 누적_토큰이_문턱값과_같으면_생성_가능하다() {
        SummaryDraftEligibility eligibility =
                policyWithThreshold(500).evaluate(activeSessionWithTokens(500));

        assertThat(eligibility.eligible()).isTrue();
    }

    @Test
    void 진행률은_문턱값_대비_백분율이고_100_을_넘지_않는다() {
        assertThat(policyWithThreshold(500).calculateProgressPercent(250)).isEqualTo(50);
        assertThat(policyWithThreshold(500).calculateProgressPercent(600)).isEqualTo(100);
    }

    private AiChatSession activeSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedActiveSession(
                SESSION_ID, USER_BOOK_ID, 0, accumulatedTokens, null
        );
    }

    private AiChatSession lockedSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedSummarizedSession(
                SESSION_ID, USER_BOOK_ID, 0, accumulatedTokens, null
        );
    }
}
