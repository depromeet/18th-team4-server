package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;
import com.readum.domain.aiChat.dto.SummaryDraftEligibilityResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.userBook.entity.UserBook;
import com.readum.model.userBook.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SummaryDraftSearchServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long USER_BOOK_ID = 1L;
    private static final int SUFFICIENT_TOKENS = 600;
    private static final int INSUFFICIENT_TOKENS = 100;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @Spy
    private SummaryDraftPolicy summaryDraftPolicy = new SummaryDraftPolicy();

    @Mock
    private SummaryJobRepository summaryJobRepository;

    @InjectMocks
    private SummaryDraftSearchService summaryDraftSearchService;

    @Test
    void 세션이_없으면_NotFoundException이_발생한다() {
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftSearchService.findEligibility(SESSION_ID, USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verifyNoInteractions(userBookRepository);
    }

    @Test
    void 다른_사용자의_세션이면_NotFoundException이_발생한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftSearchService.findEligibility(SESSION_ID, USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 정상_요청시_eligible_true와_진행률을_반환한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(SESSION_ID, USER_ID);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.message()).isNull();
        assertThat(result.progressPercent()).isEqualTo(100);
    }

    @Test
    void 잠긴_세션이면_eligible_false와_ALREADY_SUMMARIZED를_반환한다() {
        AiChatSession session = lockedSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(SESSION_ID, USER_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.ALREADY_SUMMARIZED.name());
        assertThat(result.message()).isEqualTo(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED.getMessage());
    }

    @Test
    void 활성_작업이_있으면_eligible_false와_SUMMARY_IN_PROGRESS를_반환한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(summaryJobRepository.existsByActiveSessionId(SESSION_ID)).willReturn(true);

        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(SESSION_ID, USER_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.SUMMARY_IN_PROGRESS.name());
        assertThat(result.message()).isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS.getMessage());
    }

    @Test
    void 토큰이_부족하면_eligible_false와_진행률을_반환한다() {
        AiChatSession session = activeSession(INSUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(SESSION_ID, USER_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH.name());
        assertThat(result.message()).isEqualTo(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH.getMessage());
        assertThat(result.progressPercent()).isEqualTo(20);
    }

    @Test
    void 락을_거는_findByIdForUpdate는_호출되지_않고_세션도_종료되지_않는다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        summaryDraftSearchService.findEligibility(SESSION_ID, USER_ID);

        verify(aiChatSessionRepository, never()).findByIdForUpdate(SESSION_ID);
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
    }

    private AiChatSession activeSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedActiveSession(
                SESSION_ID, USER_BOOK_ID, 0, accumulatedTokens, null
        );
    }

    private AiChatSession lockedSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedSummarizedSession(
                SESSION_ID, USER_BOOK_ID, 10, accumulatedTokens, "마지막 메시지"
        );
    }
}
