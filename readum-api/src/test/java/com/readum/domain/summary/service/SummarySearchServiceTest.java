package com.readum.domain.summary.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.MonthlyReadingRecordResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.exception.SummaryErrorCode;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.aiChat.repository.projection.SessionLastChattedProjection;
import com.readum.model.book.entity.Book;
import com.readum.model.book.entity.BookFixture;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryFixture;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.book.repository.BookRepository;
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

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SummarySearchServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @Mock
    private SummaryJobRepository summaryJobRepository;

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

    private static final LocalDateTime JUNE_11 = LocalDateTime.of(2026, 6, 11, 14, 32, 5);

    @Test
    void 월별_조회는_마지막_채팅일이_해당_월인_세션을_책제목_채팅제목_summaryId_와_함께_반환한다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1000L, 10L, 3, 100, "채팅 제목");
        Summary summary = SummaryFixture.persistedSummary(17L, 10L, 1000L, "감상문 제목", "감상문 본문");

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(aiChatSessionRepository.findByUserBookIdIn(List.of(10L))).willReturn(List.of(session));
        given(aiChatMessageRepository.findLastChattedAtBySessionIds(List.of(1000L), AiChatMessage.Status.COMPLETED))
                .willReturn(List.of(new SessionLastChattedProjection(1000L, JUNE_11)));
        given(summaryRepository.findLatestByAiChatSessionIdIn(List.of(1000L))).willReturn(List.of(summary));
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).hasSize(1);
        MonthlyReadingRecordResult record = results.get(0);
        assertThat(record.chatSessionId()).isEqualTo(1000L);
        assertThat(record.summaryId()).isEqualTo(17L);
        assertThat(record.bookTitle()).isEqualTo("테스트 책");
        assertThat(record.chatSummary()).isEqualTo("채팅 제목");
        assertThat(record.lastChattedAt()).isEqualTo(JUNE_11);
    }

    @Test
    void 감상문이_없는_세션도_summaryId_가_null_로_포함된다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(1000L, 10L, 3, 100, "채팅 제목");

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(aiChatSessionRepository.findByUserBookIdIn(List.of(10L))).willReturn(List.of(session));
        given(aiChatMessageRepository.findLastChattedAtBySessionIds(List.of(1000L), AiChatMessage.Status.COMPLETED))
                .willReturn(List.of(new SessionLastChattedProjection(1000L, JUNE_11)));
        given(summaryRepository.findLatestByAiChatSessionIdIn(List.of(1000L))).willReturn(List.of());
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).summaryId()).isNull();
        assertThat(results.get(0).chatSessionId()).isEqualTo(1000L);
    }

    @Test
    void COMPLETED_메시지가_없어_마지막_채팅일이_없는_세션은_제외된다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        AiChatSession chatted = AiChatSessionFixture.persistedActiveSession(1000L, 10L, 3, 100, "대화함");
        AiChatSession empty = AiChatSessionFixture.persistedActiveSession(1001L, 10L, 0, 0, null);

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(aiChatSessionRepository.findByUserBookIdIn(List.of(10L)))
                .willReturn(List.of(chatted, empty));
        given(aiChatMessageRepository.findLastChattedAtBySessionIds(
                List.of(1000L, 1001L), AiChatMessage.Status.COMPLETED))
                .willReturn(List.of(new SessionLastChattedProjection(1000L, JUNE_11)));
        given(summaryRepository.findLatestByAiChatSessionIdIn(List.of(1000L))).willReturn(List.of());
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).extracting(MonthlyReadingRecordResult::chatSessionId).containsExactly(1000L);
    }

    @Test
    void 마지막_채팅일이_조회_월_밖인_세션은_제외된다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        AiChatSession june = AiChatSessionFixture.persistedActiveSession(1000L, 10L, 3, 100, "6월 대화");
        AiChatSession may = AiChatSessionFixture.persistedActiveSession(1001L, 10L, 3, 100, "5월 대화");

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(aiChatSessionRepository.findByUserBookIdIn(List.of(10L)))
                .willReturn(List.of(june, may));
        given(aiChatMessageRepository.findLastChattedAtBySessionIds(
                List.of(1000L, 1001L), AiChatMessage.Status.COMPLETED))
                .willReturn(List.of(
                        new SessionLastChattedProjection(1000L, JUNE_11),
                        new SessionLastChattedProjection(1001L, LocalDateTime.of(2026, 5, 31, 23, 59, 59))));
        given(summaryRepository.findLatestByAiChatSessionIdIn(List.of(1000L))).willReturn(List.of());
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).extracting(MonthlyReadingRecordResult::chatSessionId).containsExactly(1000L);
    }

    @Test
    void 월별_조회는_마지막_채팅일_내림차순으로_정렬한다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        AiChatSession older = AiChatSessionFixture.persistedActiveSession(1000L, 10L, 3, 100, "오래된 대화");
        AiChatSession newer = AiChatSessionFixture.persistedActiveSession(1001L, 10L, 3, 100, "최근 대화");

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(aiChatSessionRepository.findByUserBookIdIn(List.of(10L)))
                .willReturn(List.of(older, newer));
        given(aiChatMessageRepository.findLastChattedAtBySessionIds(
                List.of(1000L, 1001L), AiChatMessage.Status.COMPLETED))
                .willReturn(List.of(
                        new SessionLastChattedProjection(1000L, LocalDateTime.of(2026, 6, 5, 9, 0, 0)),
                        new SessionLastChattedProjection(1001L, LocalDateTime.of(2026, 6, 20, 9, 0, 0))));
        given(summaryRepository.findLatestByAiChatSessionIdIn(List.of(1000L, 1001L))).willReturn(List.of());
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).extracting(MonthlyReadingRecordResult::chatSessionId)
                .containsExactly(1001L, 1000L);
    }

    @Test
    void 월_경계는_월초_00시와_월말_자정직전을_포함하고_다음달_00시는_제외한다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        AiChatSession monthStart = AiChatSessionFixture.persistedActiveSession(1000L, 10L, 3, 100, "월초");
        AiChatSession monthEnd = AiChatSessionFixture.persistedActiveSession(1001L, 10L, 3, 100, "월말");
        AiChatSession nextMonth = AiChatSessionFixture.persistedActiveSession(1002L, 10L, 3, 100, "다음달 0시");

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(aiChatSessionRepository.findByUserBookIdIn(List.of(10L)))
                .willReturn(List.of(monthStart, monthEnd, nextMonth));
        given(aiChatMessageRepository.findLastChattedAtBySessionIds(
                List.of(1000L, 1001L, 1002L), AiChatMessage.Status.COMPLETED))
                .willReturn(List.of(
                        new SessionLastChattedProjection(1000L, LocalDateTime.of(2026, 6, 1, 0, 0, 0)),
                        new SessionLastChattedProjection(1001L, LocalDateTime.of(2026, 6, 30, 23, 59, 59)),
                        new SessionLastChattedProjection(1002L, LocalDateTime.of(2026, 7, 1, 0, 0, 0))));
        given(summaryRepository.findLatestByAiChatSessionIdIn(List.of(1000L, 1001L))).willReturn(List.of());
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        // 월말(1001)이 최신순으로 앞, 월초(1000)가 뒤. 다음달 0시(1002)는 제외.
        assertThat(results).extracting(MonthlyReadingRecordResult::chatSessionId)
                .containsExactly(1001L, 1000L);
    }

    @Test
    void 마지막_채팅_시각이_같으면_chatSessionId_내림차순으로_정렬한다() {
        UserBook userBook = UserBookFixture.persistedUserBook(10L, USER_ID, 100L);
        Book book = BookFixture.persistedBook(
                100L, "ext-1", "테스트 책", "저자", "출판사", 2024, "http://example.com/c.jpg");
        AiChatSession lower = AiChatSessionFixture.persistedActiveSession(1000L, 10L, 3, 100, "같은 시각 A");
        AiChatSession higher = AiChatSessionFixture.persistedActiveSession(1001L, 10L, 3, 100, "같은 시각 B");

        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of(userBook));
        given(aiChatSessionRepository.findByUserBookIdIn(List.of(10L)))
                .willReturn(List.of(lower, higher));
        given(aiChatMessageRepository.findLastChattedAtBySessionIds(
                List.of(1000L, 1001L), AiChatMessage.Status.COMPLETED))
                .willReturn(List.of(
                        new SessionLastChattedProjection(1000L, JUNE_11),
                        new SessionLastChattedProjection(1001L, JUNE_11)));
        given(summaryRepository.findLatestByAiChatSessionIdIn(List.of(1000L, 1001L))).willReturn(List.of());
        given(bookRepository.findAllById(List.of(100L))).willReturn(List.of(book));

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).extracting(MonthlyReadingRecordResult::chatSessionId)
                .containsExactly(1001L, 1000L);
    }

    @Test
    void 등록한_책이_없으면_세션_조회_없이_빈_리스트를_반환한다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(userBookRepository.findByUserId(USER_ID)).willReturn(List.of());

        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(JUNE, USER_SESSION_ID);

        assertThat(results).isEmpty();
        verifyNoInteractions(aiChatSessionRepository, aiChatMessageRepository, summaryRepository, bookRepository);
    }

    @Test
    void 월별_조회_시_세션이_유효하지_않으면_UnauthorizedException_을_던진다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findMonthly(JUNE, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }

    @Test
    void 상세_조회는_본인_소유의_감상문을_반환한다() {
        Summary summary = SummaryFixture.persistedSummary(17L, 10L, 1000L, "감상문 제목", "감상문 본문");
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(summaryRepository.findById(17L)).willReturn(Optional.of(summary));
        given(userBookRepository.findByIdAndUserId(10L, USER_ID))
                .willReturn(Optional.of(UserBookFixture.persistedUserBook(10L, USER_ID, 100L)));

        SummaryResult result = summarySearchService.findById(17L, USER_SESSION_ID);

        assertThat(result.aiChatSessionId()).isEqualTo(1000L);
        assertThat(result.title()).isEqualTo("감상문 제목");
        assertThat(result.body()).isEqualTo("감상문 본문");
    }

    @Test
    void 상세_조회_시_세션이_유효하지_않으면_UnauthorizedException_을_던진다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findById(17L, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }

    @Test
    void 존재하지_않는_감상문을_상세_조회하면_NotFoundException_을_던진다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(summaryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findById(99L, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(SummaryErrorCode.SUMMARY_NOT_FOUND);
    }

    @Test
    void 남의_감상문을_상세_조회하면_NotFoundException_을_던진다() {
        Summary summary = SummaryFixture.persistedSummary(17L, 10L, 1000L, "감상문 제목", "감상문 본문");
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(summaryRepository.findById(17L)).willReturn(Optional.of(summary));
        given(userBookRepository.findByIdAndUserId(10L, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findById(17L, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(SummaryErrorCode.SUMMARY_NOT_FOUND);
    }

    private static final Long SESSION_ID = 1000L;

    @Test
    void 세션_ID로_조회는_감상문을_반환한다() {
        AiChatSession active = AiChatSessionFixture.persistedActiveSession(SESSION_ID, 10L, 3, 100, null);
        Summary summary = SummaryFixture.persistedSummary(17L, 10L, SESSION_ID, "감상문 제목", "감상문 본문");
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(active));
        given(summaryJobRepository.existsBlockingSummaryJob(eq(SESSION_ID), any(LocalDateTime.class)))
                .willReturn(false);
        given(summaryRepository.findByAiChatSessionId(SESSION_ID)).willReturn(Optional.of(summary));

        SummaryResult result = summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID);

        assertThat(result.title()).isEqualTo("감상문 제목");
        assertThat(result.body()).isEqualTo("감상문 본문");
        assertThat(result.aiChatSessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    void 세션_ID로_조회_시_차단_작업이_있으면_ConflictException_을_던진다() {
        AiChatSession active = AiChatSessionFixture.persistedActiveSession(SESSION_ID, 10L, 3, 100, null);
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(active));
        given(summaryJobRepository.existsBlockingSummaryJob(eq(SESSION_ID), any(LocalDateTime.class)))
                .willReturn(true);

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(SummaryErrorCode.SUMMARY_IN_PROGRESS);
    }

    @Test
    void 세션_ID로_조회_시_감상문이_없으면_NotFoundException_을_던진다() {
        AiChatSession active = AiChatSessionFixture.persistedActiveSession(SESSION_ID, 10L, 3, 100, null);
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(active));
        given(summaryJobRepository.existsBlockingSummaryJob(eq(SESSION_ID), any(LocalDateTime.class)))
                .willReturn(false);
        given(summaryRepository.findByAiChatSessionId(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(SummaryErrorCode.SUMMARY_NOT_YET_CREATED);
    }

    @Test
    void 세션_ID로_조회_시_세션이_없거나_소유권이_없으면_NotFoundException_을_던진다() {
        given(userRepository.findBySessionId(USER_SESSION_ID)).willReturn(Optional.of(stubUser()));
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> summarySearchService.findBySessionId(SESSION_ID, USER_SESSION_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }
}
