package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryDraftServiceTest {

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
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private AiSummaryClient aiSummaryClient;

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @Spy
    private SummaryDraftPolicy summaryDraftPolicy = new SummaryDraftPolicy();

    private SummaryDraftService summaryDraftService;

    @BeforeEach
    void setUp() {
        User testUser = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
        summaryDraftService = new SummaryDraftService(
                userRepository,
                aiChatSessionRepository,
                aiChatMessageRepository,
                aiSummaryClient,
                userBookRepository,
                summaryRepository,
                summaryDraftPolicy,
                new NoopTransactionManager()
        );
    }

    @Test
    void 정상_요청시_COMPLETED_감상문을_새_행으로_저장하고_세션을_다시_활성화한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        List<AiChatMessage> messages = List.of(
                userMessage(SESSION_ID, "이 책에서 가장 인상 깊은 장면은?"),
                assistantMessage(SESSION_ID, "주인공이 선택의 기로에 서는 장면이 인상적입니다.")
        );
        SummaryDraftResult expected = new SummaryDraftResult("나의 독서 감상", "깊은 울림을 주는 책이었다.", "선택의 기로에서");

        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(messages);
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(aiSummaryClient.generate(messages)).willReturn(expected);

        summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

        ArgumentCaptor<Summary> summaryCaptor = ArgumentCaptor.forClass(Summary.class);
        verify(summaryRepository).save(summaryCaptor.capture());
        Summary saved = summaryCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Summary.Status.COMPLETED);
        assertThat(saved.getAiChatSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getTitle()).isEqualTo("나의 독서 감상");
        assertThat(saved.getBody()).isEqualTo("깊은 울림을 주는 책이었다.");
        assertThat(saved.getQuote()).isEqualTo("선택의 기로에서");
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
    }

    @Test
    void 생성이_진행되는_동안에는_세션이_잠겨있다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        SummaryDraftResult expected = new SummaryDraftResult("제목", "본문", "인용");

        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(List.of());
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        // LLM 호출 시점(생성 진행 중)의 세션 상태를 캡처해 두고, 호출이 끝난 뒤 바깥에서 단언한다.
        AtomicReference<AiChatSession.Status> statusDuringGeneration = new AtomicReference<>();
        given(aiSummaryClient.generate(any())).willAnswer(invocation -> {
            statusDuringGeneration.set(session.getStatus());
            return expected;
        });

        summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

        assertThat(statusDuringGeneration.get()).isEqualTo(AiChatSession.Status.LOCKED);
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
    }

    @Test
    void AI_호출_실패시_FAILED_감상문을_새_행으로_저장하고_세션을_다시_활성화한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);

        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(List.of());
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(aiSummaryClient.generate(any())).willThrow(new RuntimeException("AI 오류"));

        summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

        ArgumentCaptor<Summary> summaryCaptor = ArgumentCaptor.forClass(Summary.class);
        verify(summaryRepository).save(summaryCaptor.capture());
        Summary saved = summaryCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Summary.Status.FAILED);
        assertThat(saved.getAiChatSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getTitle()).isNull();
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
    }

    @Test
    void 세션이_없으면_NotFoundException이_발생한다() {
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verify(aiSummaryClient, never()).generate(any());
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void 다른_사용자의_세션이면_NotFoundException이_발생한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verify(aiSummaryClient, never()).generate(any());
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void 잠긴_세션이면_ConflictException이_발생한다() {
        AiChatSession lockedSession = AiChatSessionFixture.persistedLockedSession(
                SESSION_ID, USER_BOOK_ID, 10, SUFFICIENT_TOKENS, "마지막 메시지"
        );
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(lockedSession));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS);

        verify(aiSummaryClient, never()).generate(any());
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void 누적_토큰이_임계값_미만이면_UnprocessableEntityException이_발생한다() {
        AiChatSession session = activeSession(INSUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(UnprocessableEntityException.class))
                .extracting(UnprocessableEntityException::getErrorCode)
                .isEqualTo(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);

        verify(aiSummaryClient, never()).generate(any());
        verify(summaryRepository, never()).save(any());
    }

    private AiChatSession activeSession(int accumulatedTokens) {
        return AiChatSessionFixture.persistedActiveSession(
                SESSION_ID, USER_BOOK_ID, 0, accumulatedTokens, null
        );
    }

    private AiChatMessage userMessage(Long sessionId, String content) {
        return AiChatMessageFixture.userMessageWithTokens(sessionId, content, 10, 10);
    }

    private AiChatMessage assistantMessage(Long sessionId, String content) {
        return AiChatMessageFixture.assistantMessageWithTokens(sessionId, content, 40, 40);
    }

    /**
     * TransactionTemplate 가 콜백을 그대로 실행하도록 한 테스트 전용 noop 매니저.
     */
    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
