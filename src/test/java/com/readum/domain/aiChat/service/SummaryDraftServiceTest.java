package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.EnqueueSummaryJobResult;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.entity.UserBookFixture;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryDraftServiceTest {

    private static final String USER_SESSION_ID = "user-session";
    private static final Long USER_ID = 10L;
    private static final Long SESSION_ID = 1L;
    private static final Long USER_BOOK_ID = 5L;
    private static final Long BOOK_ID = 99L;

    @Mock private UserRepository userRepository;
    @Mock private AiChatSessionRepository aiChatSessionRepository;
    @Mock private UserBookRepository userBookRepository;
    @Mock private SummaryDraftPolicy summaryDraftPolicy;
    @Mock private SummaryJobRepository summaryJobRepository;
    @Mock private EnqueueSummaryJobService enqueueSummaryJobService;

    @InjectMocks private SummaryDraftService summaryDraftService;

    @Test
    void 자격을_통과하면_작업을_적재한다() {
        User user = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 2, 600, "제목");
        UserBook userBook = UserBookFixture.persistedUserBook(USER_BOOK_ID, USER_ID, BOOK_ID);
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(user));
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(userBook));
        given(enqueueSummaryJobService.execute(SESSION_ID)).willReturn(new EnqueueSummaryJobResult(true));

        summaryDraftService.execute(SESSION_ID, USER_SESSION_ID);

        verify(enqueueSummaryJobService).execute(SESSION_ID);
    }

    @Test
    void 적재_경합으로_적재되지_않으면_409_SUMMARY_IN_PROGRESS() {
        User user = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 2, 600, "제목");
        UserBook userBook = UserBookFixture.persistedUserBook(USER_BOOK_ID, USER_ID, BOOK_ID);
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(user));
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(userBook));
        given(enqueueSummaryJobService.execute(SESSION_ID)).willReturn(new EnqueueSummaryJobResult(false));

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS);
    }

    @Test
    void 사용자_세션이_유효하지_않으면_401() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .isInstanceOf(UnauthorizedException.class);
        verify(enqueueSummaryJobService, never()).execute(anyLong());
    }

    @Test
    void 세션이_없으면_NotFoundException이_발생한다() {
        User user = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(user));
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .isInstanceOf(NotFoundException.class);
        verify(enqueueSummaryJobService, never()).execute(anyLong());
    }

    @Test
    void 다른_사용자의_세션이면_NotFoundException이_발생한다() {
        User user = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 2, 600, "제목");
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(user));
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .isInstanceOf(NotFoundException.class);
        verify(enqueueSummaryJobService, never()).execute(anyLong());
    }

    @Test
    void 자격검증에_실패하면_적재하지_않고_예외를_전파한다() {
        User user = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 2, 600, "제목");
        UserBook userBook = UserBookFixture.persistedUserBook(USER_BOOK_ID, USER_ID, BOOK_ID);
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(user));
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(userBook));
        willThrow(new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS))
                .given(summaryDraftPolicy).assertEligible(session);

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .isInstanceOf(ConflictException.class);
        verify(enqueueSummaryJobService, never()).execute(anyLong());
    }

    @Test
    void 이미_활성_작업이_있으면_409_SUMMARY_IN_PROGRESS_이고_적재하지_않는다() {
        User user = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(SESSION_ID, USER_BOOK_ID, 2, 600, "제목");
        UserBook userBook = UserBookFixture.persistedUserBook(USER_BOOK_ID, USER_ID, BOOK_ID);
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(user));
        given(aiChatSessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID)).willReturn(Optional.of(userBook));
        given(summaryJobRepository.existsByActiveSessionId(SESSION_ID)).willReturn(true);

        assertThatThrownBy(() -> summaryDraftService.execute(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        verify(enqueueSummaryJobService, never()).execute(anyLong());
    }
}
