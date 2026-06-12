package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;
import com.readum.domain.aiChat.dto.SummaryDraftEligibilityResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SummaryDraftSearchServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String USER_SESSION_ID = "test-session-id";
    private static final Long USER_BOOK_ID = 1L;
    private static final int SUFFICIENT_TOKENS = 600;
    private static final int INSUFFICIENT_TOKENS = 100;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @Spy
    private SummaryDraftPolicy summaryDraftPolicy = new SummaryDraftPolicy();

    @InjectMocks
    private SummaryDraftSearchService summaryDraftSearchService;

    @BeforeEach
    void setUp() {
        User testUser = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
    }

    @Test
    void 유효하지_않은_세션이면_UnauthorizedException() {
        given(userRepository.findBySessionId("invalid")).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftSearchService.findEligibility(SESSION_ID, "invalid"))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }

    @Test
    void 세션이_없으면_NotFoundException이_발생한다() {
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftSearchService.findEligibility(SESSION_ID, USER_SESSION_ID))
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

        assertThatThrownBy(() -> summaryDraftSearchService.findEligibility(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 정상_요청시_eligible_true_결과를_반환한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(SESSION_ID, USER_SESSION_ID);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.message()).isNull();
    }

    @Test
    void 잠긴_세션이면_eligible_false와_SUMMARY_IN_PROGRESS를_반환한다() {
        AiChatSession session = lockedSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(SESSION_ID, USER_SESSION_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.SUMMARY_IN_PROGRESS.name());
        assertThat(result.message()).isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS.getMessage());
    }

    @Test
    void 토큰이_부족하면_eligible_false와_CHAT_VOLUME_NOT_ENOUGH를_반환한다() {
        AiChatSession session = activeSession(INSUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(SESSION_ID, USER_SESSION_ID);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(IneligibleReason.CHAT_VOLUME_NOT_ENOUGH.name());
        assertThat(result.message()).isEqualTo(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH.getMessage());
    }

    @Test
    void 락을_거는_findByIdForUpdate는_호출되지_않고_세션도_종료되지_않는다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        summaryDraftSearchService.findEligibility(SESSION_ID, USER_SESSION_ID);

        verify(aiChatSessionRepository, never()).findByIdForUpdate(SESSION_ID);
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
    }

    private AiChatSession activeSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedActiveSession(
                SESSION_ID, USER_BOOK_ID, 0, accumulatedTokens, null
        );
    }

    private AiChatSession lockedSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedLockedSession(
                SESSION_ID, USER_BOOK_ID, 10, accumulatedTokens, "마지막 메시지"
        );
    }
}
