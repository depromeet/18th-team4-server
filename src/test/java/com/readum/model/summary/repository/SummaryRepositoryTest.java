package com.readum.model.summary.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.Book;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SummaryRepositoryTest {

    @Autowired
    private SummaryRepository summaryRepository;

    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;

    @Autowired
    private UserBookRepository userBookRepository;

    @Autowired
    private BookRepository bookRepository;

    private static long sessionIdSeq = 900_000L;
    private static long userIdSeq = 9_200_000L;
    private static long externalSeq = 9_200_000L;

    private static synchronized long nextSessionId() {
        return sessionIdSeq++;
    }

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }

    private static synchronized String nextExternalId() {
        externalSeq += 1;
        return "ext-" + externalSeq;
    }

    // ===== 월별 달력 조회 (findMonthlyCompleted) — Summary 자기 컬럼만으로 좁히는 derived query =====

    private Summary persistCompleted(Long userBookId, LocalDate summaryDate) {
        Summary summary = summaryRepository.save(
                Summary.createInProgress(userBookId, nextSessionId(), summaryDate));
        summary.complete("제목", "본문");
        return summaryRepository.save(summary);
    }

    @Test
    @DisplayName("월 범위(월초·월말 포함)의 감상문만 반환한다")
    void findMonthlyCompleted_월_경계_포함() {
        Long userBookId = 1L;
        Summary firstDay = persistCompleted(userBookId, LocalDate.of(2026, 6, 1));
        Summary lastDay = persistCompleted(userBookId, LocalDate.of(2026, 6, 30));
        persistCompleted(userBookId, LocalDate.of(2026, 5, 31));
        persistCompleted(userBookId, LocalDate.of(2026, 7, 1));

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(userBookId), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(found).extracting(Summary::getId)
                .containsExactly(lastDay.getId(), firstDay.getId());   // 최신순: 6/30 이 먼저
    }

    @Test
    @DisplayName("COMPLETED 가 아닌 감상문은 반환하지 않는다")
    void findMonthlyCompleted_미완성_제외() {
        Long userBookId = 2L;
        LocalDate date = LocalDate.of(2026, 6, 10);
        Summary completed = persistCompleted(userBookId, date);
        summaryRepository.save(Summary.createInProgress(userBookId, nextSessionId(), date)); // IN_PROGRESS
        Summary failed = summaryRepository.save(Summary.createInProgress(userBookId, nextSessionId(), date));
        failed.fail();
        summaryRepository.save(failed);

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(userBookId), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(found).extracting(Summary::getId).containsExactly(completed.getId());
    }

    @Test
    @DisplayName("조회 대상 userBookId 가 아닌 감상문은 반환하지 않는다")
    void findMonthlyCompleted_다른_userBook_제외() {
        Long mine = 3L;
        Long others = 4L;
        LocalDate date = LocalDate.of(2026, 6, 10);
        Summary myRecord = persistCompleted(mine, date);
        persistCompleted(others, date);

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(mine), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(found).extracting(Summary::getId).containsExactly(myRecord.getId());
    }

    @Test
    @DisplayName("최신순으로 정렬한다 — summaryDate 내림차순, 같은 날짜는 생성 시각·id 내림차순")
    void findMonthlyCompleted_최신순_정렬() {
        Long userBookId = 5L;
        Summary laterDate = persistCompleted(userBookId, LocalDate.of(2026, 6, 20));
        Summary sameDayEarlier = persistCompleted(userBookId, LocalDate.of(2026, 6, 5));
        Summary sameDayLater = persistCompleted(userBookId, LocalDate.of(2026, 6, 5));

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(userBookId), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // 6/20 이 맨 앞, 같은 6/5 안에서는 나중에 만든 기록(생성 시각이 같으면 id 가 큰 쪽)이 먼저
        assertThat(found).extracting(Summary::getId)
                .containsExactly(laterDate.getId(), sameDayLater.getId(), sameDayEarlier.getId());
    }

    // ===== 전체 감상 기록 목록 (findCompletedHistoryByUserId) — 세션 상태 필터 + 페이지네이션 join projection =====

    @Test
    @DisplayName("findCompletedHistoryByUserId: 종료된 세션의 완성된 감상문을 책 제목·본문·생성일과 함께 조회한다")
    void 종료_세션의_완성_감상문을_조회한다() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "데미안");
        AiChatSession session = persistSession(userBookId, AiChatSession.Status.CLOSED);
        persistCompletedSummary(userBookId, session.getId(), "내 안에서 솟아 나오려는 것");

        Slice<SummaryHistoryProjection> slice = summaryRepository
                .findCompletedHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent()).hasSize(1);
        SummaryHistoryProjection row = slice.getContent().get(0);
        assertThat(row.bookTitle()).isEqualTo("데미안");
        assertThat(row.body()).isEqualTo("내 안에서 솟아 나오려는 것");
        assertThat(row.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("findCompletedHistoryByUserId: 아직 대화 중(ACTIVE)인 세션에 자동 생성된 완성 감상문은 제외된다")
    void 활성_세션의_완성_감상문은_제외된다() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "활성책");
        // 스케줄러가 ACTIVE 세션에 미리 만들어 둔 COMPLETED 감상문 — 세션이 아직 안 닫혔으므로 기록에 노출되면 안 된다.
        AiChatSession session = persistSession(userBookId, AiChatSession.Status.ACTIVE);
        persistCompletedSummary(userBookId, session.getId(), "스케줄러 자동 생성 감상");

        Slice<SummaryHistoryProjection> slice = summaryRepository
                .findCompletedHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findCompletedHistoryByUserId: 미완성(IN_PROGRESS)·실패(FAILED) 감상문은 제외된다")
    void 미완성_실패_감상문은_제외된다() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "진행중책");

        AiChatSession inProgressSession = persistSession(userBookId, AiChatSession.Status.CLOSED);
        summaryRepository.save(Summary.createInProgress(userBookId, inProgressSession.getId(), LocalDate.now()));

        AiChatSession failedSession = persistSession(userBookId, AiChatSession.Status.CLOSED);
        Summary failed = summaryRepository.save(
                Summary.createInProgress(userBookId, failedSession.getId(), LocalDate.now()));
        failed.fail();
        summaryRepository.saveAndFlush(failed);

        Slice<SummaryHistoryProjection> slice = summaryRepository
                .findCompletedHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findCompletedHistoryByUserId: 다른 사용자의 감상문은 조회되지 않는다")
    void 다른_사용자_감상문은_제외된다() {
        Long owner = nextUserId();
        Long other = nextUserId();
        Long ownerBookId = persistUserBook(owner, "주인책");
        AiChatSession session = persistSession(ownerBookId, AiChatSession.Status.CLOSED);
        persistCompletedSummary(ownerBookId, session.getId(), "주인의 감상");

        Slice<SummaryHistoryProjection> slice = summaryRepository
                .findCompletedHistoryByUserId(other, PageRequest.of(0, 20));

        assertThat(slice.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findCompletedHistoryByUserId: 최신순(생성일·id 내림차순)으로 정렬된다")
    void 최신순으로_정렬된다() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "한권");

        persistClosedCompletedSummary(userBookId, "첫번째");
        persistClosedCompletedSummary(userBookId, "두번째");
        persistClosedCompletedSummary(userBookId, "세번째");

        Slice<SummaryHistoryProjection> slice = summaryRepository
                .findCompletedHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent())
                .extracting(SummaryHistoryProjection::body)
                .containsExactly("세번째", "두번째", "첫번째");
    }

    @Test
    @DisplayName("findCompletedHistoryByUserId: Slice 의 hasNext 가 페이지 size 와 비교해 반환된다")
    void hasNext_페이지네이션() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "여러권");
        for (int i = 0; i < 3; i++) {
            persistClosedCompletedSummary(userBookId, "감상" + i);
        }

        Slice<SummaryHistoryProjection> firstPage = summaryRepository
                .findCompletedHistoryByUserId(userId, PageRequest.of(0, 2));
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();

        Slice<SummaryHistoryProjection> secondPage = summaryRepository
                .findCompletedHistoryByUserId(userId, PageRequest.of(1, 2));
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findCompletedHistoryByUserId: 감상 기록이 없으면 빈 Slice 를 반환한다")
    void 기록이_없으면_빈_Slice() {
        Long userId = nextUserId();
        persistUserBook(userId, "빈책");

        Slice<SummaryHistoryProjection> slice = summaryRepository
                .findCompletedHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent()).isEmpty();
        assertThat(slice.hasNext()).isFalse();
    }

    private Long persistUserBook(Long userId, String bookTitle) {
        Book book = bookRepository.save(
                Book.create(nextExternalId(), bookTitle, null, null, null, null));
        UserBook userBook = userBookRepository.save(UserBook.create(userId, book.getId()));
        return userBook.getId();
    }

    private AiChatSession persistSession(Long userBookId, AiChatSession.Status status) {
        AiChatSession session = AiChatSession.create(userBookId);
        if (status == AiChatSession.Status.CLOSED) {
            session.close();
        }
        return aiChatSessionRepository.save(session);
    }

    private Summary persistCompletedSummary(Long userBookId, Long sessionId, String body) {
        Summary summary = summaryRepository.save(
                Summary.createInProgress(userBookId, sessionId, LocalDate.now()));
        summary.complete("제목", body);
        return summaryRepository.saveAndFlush(summary);
    }

    private Summary persistClosedCompletedSummary(Long userBookId, String body) {
        AiChatSession session = persistSession(userBookId, AiChatSession.Status.CLOSED);
        return persistCompletedSummary(userBookId, session.getId(), body);
    }
}
