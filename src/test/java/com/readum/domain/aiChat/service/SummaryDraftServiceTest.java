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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SummaryDraftServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long USER_BOOK_ID = 1L;
    private static final int SUFFICIENT_TOKENS = 600;
    private static final int INSUFFICIENT_TOKENS = 100;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private AiSummaryClient aiSummaryClient;

    @Mock
    private UserBookRepository userBookRepository;

    @Spy
    private SummaryDraftPolicy summaryDraftPolicy = new SummaryDraftPolicy();

    @InjectMocks
    private SummaryDraftService summaryDraftService;

    @Test
    void 정상_요청시_감상문_초안을_반환하고_세션을_닫는다() {
        // given
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        List<AiChatMessage> messages = List.of(
                userMessage(SESSION_ID, "이 책에서 가장 인상 깊은 장면은?"),
                assistantMessage(SESSION_ID, "주인공이 선택의 기로에 서는 장면이 인상적입니다.")
        );
        SummaryDraftResult expected = new SummaryDraftResult("나의 독서 감상", "깊은 울림을 주는 책이었다.", "선택의 기로에서");

        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(SESSION_ID)).willReturn(messages);
        given(aiSummaryClient.generate(messages)).willReturn(expected);

        // when
        SummaryDraftResult result = summaryDraftService.execute(SESSION_ID, USER_ID);

        // then
        assertThat(result).isEqualTo(expected);
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.CLOSED);
    }

    @Test
    void 세션이_없으면_NotFoundException이_발생한다() {
        // given
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .isInstanceOf(NotFoundException.class)
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND));

        verifyNoInteractions(aiChatMessageRepository, aiSummaryClient);
    }

    @Test
    void 다른_사용자의_세션이면_NotFoundException이_발생한다() {
        // given
        AiChatSession session = activeSession(SUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .isInstanceOf(NotFoundException.class)
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND));

        verifyNoInteractions(aiChatMessageRepository, aiSummaryClient);
    }

    @Test
    void 이미_닫힌_세션이면_ConflictException이_발생한다() {
        // given
        AiChatSession closedSession = AiChatSession.of(
                SESSION_ID, USER_BOOK_ID, AiChatSession.Status.CLOSED,
                10, SUFFICIENT_TOKENS, "마지막 메시지", LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(closedSession));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        // when & then
        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .isInstanceOf(ConflictException.class)
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.SESSION_ALREADY_CLOSED));

        verifyNoInteractions(aiChatMessageRepository, aiSummaryClient);
    }

    @Test
    void 누적_토큰이_임계값_미만이면_UnprocessableEntityException이_발생한다() {
        // given
        AiChatSession session = activeSession(INSUFFICIENT_TOKENS);
        given(aiChatSessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(mock(UserBook.class)));

        // when & then
        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .asInstanceOf(InstanceOfAssertFactories.type(UnprocessableEntityException.class))
                .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH));

        verifyNoInteractions(aiSummaryClient);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────

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
}
