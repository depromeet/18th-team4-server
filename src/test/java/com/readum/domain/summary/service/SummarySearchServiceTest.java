package com.readum.domain.summary.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.MonthlySummaryResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.Book;
import com.readum.model.book.entity.BookFixture;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryFixture;
import com.readum.model.summary.repository.SummaryRepository;
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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SummarySearchServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private BookRepository bookRepository;

    @InjectMocks
    private SummarySearchService summarySearchService;

    private static final Long USER_ID = 1L;
    private static final String USER_SESSION_ID = "test-session-id";
    private static final YearMonth JUNE = YearMonth.of(2026, 6);

    private User stubUser() {
        return UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
    }

    @Test
    void 월별_조회는_완성된_감상문을_책_제목과_함께_반환한다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        Summary summary = SummaryFixture.persistedCompletedSummary(
                17L, 10L, 1000L, LocalDate.of(2026, 6, 11), "감상문 제목", "감상문 본문");

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(summaryRepository.findMonthlyCompleted(
                List.of(10L), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .willReturn(List.of(summary));
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlySummaryResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).summaryId()).isEqualTo(17L);
        assertThat(results.get(0).summaryDate()).isEqualTo(LocalDate.of(2026, 6, 11));
        assertThat(results.get(0).title()).isEqualTo("감상문 제목");
        assertThat(results.get(0).body()).isEqualTo("감상문 본문");
        assertThat(results.get(0).bookTitle()).isEqualTo("테스트 책");
    }

    @Test
    void 등록한_책이_없으면_감상문_조회_없이_빈_리스트를_반환한다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of());

        List<MonthlySummaryResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).isEmpty();
        verifyNoInteractions(summaryRepository, bookRepository);
    }

    @Test
    void 월별_조회_시_세션이_유효하지_않으면_UnauthorizedException_을_던진다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findMonthly(JUNE, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.INVALID_SESSION));
    }
}
