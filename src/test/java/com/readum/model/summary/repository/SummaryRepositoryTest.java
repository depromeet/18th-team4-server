package com.readum.model.summary.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.Book;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryFixture;
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

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static long userBookIdSeq = 800_000L;
    private static long userIdSeq = 9_200_000L;
    private static long externalSeq = 9_200_000L;

    private static synchronized long nextSessionId() {
        return sessionIdSeq++;
    }

    private static synchronized Long nextUserBookId() {
        return userBookIdSeq++;
    }

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }

    private static synchronized String nextExternalId() {
        externalSeq += 1;
        return "ext-" + externalSeq;
    }

    // ===== 세션 최신 감상문 / 기록 목록 =====

    @Test
    @DisplayName("findByAiChatSessionId: 감상문이 없으면 빈 Optional 을 반환한다")
    void 감상문_없으면_빈_Optional() {
        Long userBookId = persistUserBook(nextUserId(), "빈세션책");
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBookId));

        assertThat(summaryRepository.findByAiChatSessionId(session.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("findLatestByAiChatSessionIdIn: 여러 세션의 감상문을 세션별로 한 건씩 반환한다")
    void 세션별_최신_감상문_일괄조회() {
        Long userBookId = persistUserBook(nextUserId(), "여러세션책");
        AiChatSession sessionA = aiChatSessionRepository.save(AiChatSession.create(userBookId));
        AiChatSession sessionB = aiChatSessionRepository.save(AiChatSession.create(userBookId));

        // 1:1 모델 — 세션당 감상문은 한 행이다
        summaryRepository.save(Summary.createCompleted(userBookId, sessionA.getId(), "A 제목", "A 본문"));
        summaryRepository.save(Summary.createCompleted(userBookId, sessionB.getId(), "B 하나", "B 본문"));

        List<Summary> latest = summaryRepository.findLatestByAiChatSessionIdIn(
                List.of(sessionA.getId(), sessionB.getId()));

        assertThat(latest)
                .hasSize(2)
                .extracting(Summary::getBody)
                .containsExactlyInAnyOrder("A 본문", "B 본문");
    }

    @Test
    @DisplayName("findLatestHistoryByUserId: 세션의 감상문 1건을 책 제목·본문·생성일과 함께 조회한다")
    void 세션당_최신_감상문을_조회한다() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "데미안");
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBookId));
        // 1:1 모델 — 세션당 감상문은 한 행이다
        summaryRepository.save(Summary.createCompleted(userBookId, session.getId(), "새 제목", "내 안에서 솟아 나오려는 것"));

        Slice<SummaryHistoryProjection> slice =
                summaryRepository.findLatestHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent()).hasSize(1);
        SummaryHistoryProjection row = slice.getContent().get(0);
        assertThat(row.bookTitle()).isEqualTo("데미안");
        assertThat(row.body()).isEqualTo("내 안에서 솟아 나오려는 것");
        assertThat(row.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("findLatestHistoryByUserId: 다른 사용자의 감상문은 조회되지 않는다")
    void 다른_사용자_감상문은_제외된다() {
        Long owner = nextUserId();
        Long other = nextUserId();
        Long ownerBookId = persistUserBook(owner, "주인책");
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(ownerBookId));
        summaryRepository.save(Summary.createCompleted(ownerBookId, session.getId(), "제목", "주인의 감상"));

        Slice<SummaryHistoryProjection> slice =
                summaryRepository.findLatestHistoryByUserId(other, PageRequest.of(0, 20));

        assertThat(slice.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findLatestHistoryByUserId: 세션(=책)별 최신 1건이 최신순으로 정렬된다")
    void 최신순으로_정렬된다() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "한권");

        persistSessionWithSummary(userBookId, "첫번째");
        persistSessionWithSummary(userBookId, "두번째");
        persistSessionWithSummary(userBookId, "세번째");

        Slice<SummaryHistoryProjection> slice =
                summaryRepository.findLatestHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent())
                .extracting(SummaryHistoryProjection::body)
                .containsExactly("세번째", "두번째", "첫번째");
    }

    @Test
    @DisplayName("findLatestHistoryByUserId: Slice 의 hasNext 가 페이지 size 와 비교해 반환된다")
    void hasNext_페이지네이션() {
        Long userId = nextUserId();
        Long userBookId = persistUserBook(userId, "여러권");
        for (int i = 0; i < 3; i++) {
            persistSessionWithSummary(userBookId, "감상" + i);
        }

        Slice<SummaryHistoryProjection> firstPage =
                summaryRepository.findLatestHistoryByUserId(userId, PageRequest.of(0, 2));
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();

        Slice<SummaryHistoryProjection> secondPage =
                summaryRepository.findLatestHistoryByUserId(userId, PageRequest.of(1, 2));
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findLatestHistoryByUserId: 감상 기록이 없으면 빈 Slice 를 반환한다")
    void 기록이_없으면_빈_Slice() {
        Long userId = nextUserId();
        persistUserBook(userId, "빈책");

        Slice<SummaryHistoryProjection> slice =
                summaryRepository.findLatestHistoryByUserId(userId, PageRequest.of(0, 20));

        assertThat(slice.getContent()).isEmpty();
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    void findByAiChatSessionId_는_세션의_감상문_단건을_반환한다() {
        long sessionId = nextSessionId();
        Long userBookId = nextUserBookId();
        summaryRepository.save(SummaryFixture.persistedSummary(null, userBookId, sessionId, "제목", "본문"));

        Optional<Summary> found = summaryRepository.findByAiChatSessionId(sessionId);

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("제목");
    }

    @Test
    void 한_세션에_감상문은_하나만_저장된다() {
        long sessionId = nextSessionId();
        Long userBookId = nextUserBookId();
        summaryRepository.saveAndFlush(SummaryFixture.persistedSummary(null, userBookId, sessionId, "t1", "b1"));

        assertThatThrownBy(() ->
                        summaryRepository.saveAndFlush(
                                SummaryFixture.persistedSummary(null, userBookId, sessionId, "t2", "b2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long persistUserBook(Long userId, String bookTitle) {
        Book book = bookRepository.save(Book.create(nextExternalId(), bookTitle, null, null, null, null));
        UserBook userBook = userBookRepository.save(UserBook.create(userId, book.getId()));
        return userBook.getId();
    }

    private void persistSessionWithSummary(Long userBookId, String body) {
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBookId));
        summaryRepository.save(Summary.createCompleted(userBookId, session.getId(), "제목", body));
    }
}
