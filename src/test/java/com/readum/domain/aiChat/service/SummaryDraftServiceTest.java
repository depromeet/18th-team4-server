package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SummaryDraftServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long USER_BOOK_ID = 1L;
    private static final int SUFFICIENT_TOKENS = 600;
    private static final int INSUFFICIENT_TOKENS = 100;

    private AiChatSessionRepository aiChatSessionRepository;
    private AiChatMessageRepository aiChatMessageRepository;
    private AiSummaryClient aiSummaryClient;
    private UserBookRepository userBookRepository;
    private SummaryDraftPolicy summaryDraftPolicy;

    private SummaryDraftService summaryDraftService;

    @BeforeEach
    void setUp() {
        aiChatSessionRepository = mock(AiChatSessionRepository.class);
        aiChatMessageRepository = mock(AiChatMessageRepository.class);
        aiSummaryClient = mock(AiSummaryClient.class);
        userBookRepository = mock(UserBookRepository.class);
        summaryDraftPolicy = Mockito.spy(new SummaryDraftPolicy());

        summaryDraftService = new SummaryDraftService(
                aiChatSessionRepository,
                aiChatMessageRepository,
                userBookRepository,
                summaryDraftPolicy,
                aiSummaryClient,
                new NoopTransactionManager()
        );
    }

    @Test
    void 정상_요청시_세션을_SUMMARIZING_을_거쳐_CLOSED_로_전이하고_결과를_반환한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        List<AiChatMessage> messages = List.of(
                userMessage(SESSION_ID, "이 책에서 가장 인상 깊은 장면은?"),
                assistantMessage(SESSION_ID, "주인공이 선택의 기로에 서는 장면이 인상적입니다.")
        );
        SummaryDraftResult expected = new SummaryDraftResult("나의 독서 감상", "깊은 울림을 주는 책이었다.", "선택의 기로에서");

        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(messages);
        // markSummarizing 후 LLM 호출 시점에 세션 상태가 SUMMARIZING 인지 검증한다.
        given(aiSummaryClient.generate(messages)).willAnswer(invocation -> {
            assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.SUMMARIZING);
            return expected;
        });

        SummaryDraftResult result = summaryDraftService.execute(SESSION_ID, USER_ID);

        assertThat(result).isEqualTo(expected);
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.CLOSED);
    }

    @Test
    void 세션이_없으면_NotFoundException이_발생하고_LLM_호출은_없다() {
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verifyNoInteractions(aiChatMessageRepository, aiSummaryClient);
    }

    @Test
    void 다른_사용자의_세션이면_NotFoundException이_발생한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verifyNoInteractions(aiChatMessageRepository, aiSummaryClient);
    }

    @Test
    void 이미_닫힌_세션이면_ConflictException이_발생한다() {
        AiChatSession closedSession = AiChatSession.of(
                SESSION_ID, USER_BOOK_ID, AiChatSession.Status.CLOSED,
                10, SUFFICIENT_TOKENS, "마지막 메시지", LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(closedSession));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_ALREADY_CLOSED);

        verifyNoInteractions(aiChatMessageRepository, aiSummaryClient);
    }

    @Test
    void 이미_요약_중인_세션이면_ConflictException이_발생한다() {
        AiChatSession summarizingSession = AiChatSession.of(
                SESSION_ID, USER_BOOK_ID, AiChatSession.Status.SUMMARIZING,
                10, SUFFICIENT_TOKENS, "진행 중", LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(summarizingSession));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_ALREADY_SUMMARIZING);

        verifyNoInteractions(aiChatMessageRepository, aiSummaryClient);
    }

    @Test
    void 누적_토큰이_임계값_미만이면_UnprocessableEntityException이_발생한다() {
        AiChatSession session = activeSession(INSUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(UnprocessableEntityException.class))
                .extracting(UnprocessableEntityException::getErrorCode)
                .isEqualTo(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH);

        verifyNoInteractions(aiSummaryClient);
    }

    @Test
    void LLM_호출이_실패하면_세션을_ACTIVE_로_복구하고_예외를_전파한다() {
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        List<AiChatMessage> messages = List.of(userMessage(SESSION_ID, "한 줄"));

        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(messages);
        RuntimeException llmFailure = new RuntimeException("LLM down");
        when(aiSummaryClient.generate(messages)).thenThrow(llmFailure);

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .isSameAs(llmFailure);

        // markSummarizing 후 revertToActive 가 호출되어 ACTIVE 로 돌아왔는지 검증.
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.ACTIVE);
        // close 단계는 절대 도달하지 않는다 — findByIdForUpdate 는 prepare(1회) + revert(1회) 만.
        verify(aiChatSessionRepository, Mockito.times(2)).findByIdForUpdate(SESSION_ID);
        verify(summaryDraftPolicy, never()).assertEligible(Mockito.argThat(arg -> arg.isClosed()));
    }

    private AiChatSession activeSession(int accumulatedTokens) {
        return AiChatSession.of(
                SESSION_ID, USER_BOOK_ID, AiChatSession.Status.ACTIVE,
                0, accumulatedTokens, null, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private AiChatMessage userMessage(Long sessionId, String content) {
        return AiChatMessage.create(sessionId, AiChatMessage.Role.USER, AiChatMessage.Status.COMPLETED,
                content, null, 10, null, 10);
    }

    private AiChatMessage assistantMessage(Long sessionId, String content) {
        return AiChatMessage.create(sessionId, AiChatMessage.Role.ASSISTANT, AiChatMessage.Status.COMPLETED,
                content, null, null, 40, 40);
    }

    /**
     * TransactionTemplate 가 콜백을 그대로 실행하도록 한 테스트 전용 noop 매니저.
     * AiChatSessionTitleServiceTest 의 동일한 패턴을 재사용한다.
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
