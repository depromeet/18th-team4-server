package com.readum.domain.user.userbook.service;

import com.readum.domain.exception.NotFoundException;
import com.readum.domain.user.userbook.dto.UserBookDeleteCommand;
import com.readum.domain.user.userbook.dto.UserBookDeleteResult;
import com.readum.domain.user.userbook.exception.UserBookErrorCode;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.userBook.entity.UserBook;
import com.readum.model.userBook.entity.UserBookFixture;
import com.readum.model.userBook.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserBookDeleteServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @Mock
    private SummaryJobRepository summaryJobRepository;

    @InjectMocks
    private UserBookDeleteService userBookDeleteService;

    private static final Long USER_ID = 1L;
    private static final Long BOOK_ID = 10L;
    private static final Long USER_BOOK_ID = 100L;

    private UserBookDeleteCommand command() {
        return new UserBookDeleteCommand(USER_ID, USER_BOOK_ID);
    }

    @Test
    void 소유자_요청_시_메시지_작업_세션_요약_사용자참조_userBook_순서로_삭제하고_삭제_카운트를_반환한다() {
        UserBook userBook = UserBookFixture.persistedUserBook(USER_BOOK_ID, USER_ID, BOOK_ID);
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID))
                .willReturn(Optional.of(userBook));
        given(aiChatMessageRepository.deleteAllByUserBookId(USER_BOOK_ID)).willReturn(7);
        given(summaryJobRepository.deleteAllByUserBookId(USER_BOOK_ID)).willReturn(1);
        given(aiChatSessionRepository.deleteAllByUserBookId(USER_BOOK_ID)).willReturn(3);
        given(summaryRepository.deleteAllByUserBookId(USER_BOOK_ID)).willReturn(2);

        UserBookDeleteResult result = userBookDeleteService.execute(command());

        // 메시지 → 작업(summary_job) → 세션 → 요약 → 사용자 마지막선택 정리 → deleteById(부모) 순서.
        // 작업·메시지는 세션 서브쿼리로 좁히므로 세션 삭제 전에 지운다.
        // 마지막 단계가 delete(엔티티) 가 아니라 deleteById(id) 임을 단언 — detached 엔티티 삭제 회귀 방지.
        InOrder inOrder = inOrder(
                aiChatMessageRepository, summaryJobRepository, aiChatSessionRepository,
                summaryRepository, userRepository, userBookRepository);
        inOrder.verify(aiChatMessageRepository).deleteAllByUserBookId(USER_BOOK_ID);
        inOrder.verify(summaryJobRepository).deleteAllByUserBookId(USER_BOOK_ID);
        inOrder.verify(aiChatSessionRepository).deleteAllByUserBookId(USER_BOOK_ID);
        inOrder.verify(summaryRepository).deleteAllByUserBookId(USER_BOOK_ID);
        inOrder.verify(userRepository).clearLastSelectedUserBook(USER_BOOK_ID);
        inOrder.verify(userBookRepository).deleteById(USER_BOOK_ID);

        assertThat(result.userBookId()).isEqualTo(USER_BOOK_ID);
        assertThat(result.deletedMessages()).isEqualTo(7);
        assertThat(result.deletedSessions()).isEqualTo(3);
        assertThat(result.deletedSummaries()).isEqualTo(2);
    }

    @Test
    void 본인이_등록하지_않았거나_존재하지_않는_도서면_NotFoundException_이_발생하고_어떤_삭제도_실행되지_않는다() {
        given(userBookRepository.findByIdAndUserId(USER_BOOK_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> userBookDeleteService.execute(command()))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(UserBookErrorCode.NOT_FOUND));

        verifyNoDeletes();
    }

    private void verifyNoDeletes() {
        verify(aiChatMessageRepository, never()).deleteAllByUserBookId(anyLong());
        verify(summaryJobRepository, never()).deleteAllByUserBookId(anyLong());
        verify(aiChatSessionRepository, never()).deleteAllByUserBookId(anyLong());
        verify(summaryRepository, never()).deleteAllByUserBookId(anyLong());
        verify(userRepository, never()).clearLastSelectedUserBook(anyLong());
        verify(userBookRepository, never()).deleteById(anyLong());
    }
}
