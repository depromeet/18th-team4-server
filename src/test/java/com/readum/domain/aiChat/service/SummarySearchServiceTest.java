package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.SummaryFixture;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummarySearchServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long SUMMARY_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final String USER_SESSION_ID = "test-session-id";
    private static final Long USER_BOOK_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @InjectMocks
    private SummarySearchService summarySearchService;

    @BeforeEach
    void setUp() {
        User testUser = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
    }

    @Test
    void 최신_감상문이_있으면_내용을_반환한다() {
        givenOwnedSession(activeSession());
        given(summaryRepository.findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(SESSION_ID))
                .willReturn(Optional.of(SummaryFixture.persistedSummary(
                        SUMMARY_ID, USER_BOOK_ID, SESSION_ID, "제목", "본문")));

        SummaryResult result = summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID);

        assertThat(result.title()).isEqualTo("제목");
        assertThat(result.body()).isEqualTo("본문");
    }

    @Test
    void 세션이_잠겨있으면_생성_중_ConflictException이_발생한다() {
        givenOwnedSession(lockedSession());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS);

        // 생성 중에는 감상문 조회 자체를 하지 않는다 (직전 감상문이 있어도 노출하지 않음 — 폴링 계약 유지)
        verify(summaryRepository, never()).findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(any());
    }

    @Test
    void 감상문이_한_번도_생성되지_않았으면_NotFoundException이_발생한다() {
        givenOwnedSession(activeSession());
        given(summaryRepository.findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(SESSION_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_NOT_FOUND);
    }

    @Test
    void 세션이_없거나_소유자가_아니면_NotFoundException이_발생한다() {
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    private void givenOwnedSession(AiChatSession session) {
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(session));
    }

    private AiChatSession activeSession() {
        return AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 5, 600, "제목");
    }

    private AiChatSession lockedSession() {
        return AiChatSessionFixture.persistedLockedSession(SESSION_ID, USER_BOOK_ID, 5, 600, "제목");
    }
}
