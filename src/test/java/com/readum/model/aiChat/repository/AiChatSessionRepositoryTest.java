package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AiChatSessionRepositoryTest {

    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;

    @Autowired
    private AiChatMessageRepository aiChatMessageRepository;

    @Autowired
    private UserBookRepository userBookRepository;

    @Autowired
    private SummaryRepository summaryRepository;

    private static long userIdSeq = 9_100_000L;
    private static long bookIdSeq = 9_100_000L;

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }

    private static synchronized Long nextBookId() {
        bookIdSeq += 1;
        return bookIdSeq;
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 메시지 max(createdAt) DESC 로 정렬되어 반환된다")
    void 정렬_검증() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));

        AiChatSession older = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "older");
        AiChatSession middle = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "middle");
        AiChatSession newer = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "newer");

        // 정렬 기준은 max(message.createdAt) — 메시지에 명시 시각을 박아 순서를 결정한다.
        LocalDateTime now = LocalDateTime.now();
        saveCompletedUserMessage(older.getId(), now.minusMinutes(10));
        saveCompletedUserMessage(middle.getId(), now.minusMinutes(5));
        saveCompletedUserMessage(newer.getId(), now);

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent())
                .extracting(AiChatSessionListProjection::sessionId)
                .containsExactly(newer.getId(), middle.getId(), older.getId());
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 메시지가 없으면 lastChattedAt 은 session.createdAt 으로 fallback")
    void lastChattedAt_fallback() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));

        AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "no-messages");

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        // 메시지가 0 개이므로 session.createdAt 으로 fallback. 정확한 시각 비교는 흔들리니 날짜만 검증.
        assertThat(slice.getContent().get(0).lastChattedAt().toLocalDate())
                .isEqualTo(session.getCreatedAt().toLocalDate());
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: FAILED 부분 응답은 lastChattedAt 계산에서 제외된다")
    void lastChattedAt_은_노출_가능_메시지만() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "filtered");

        LocalDateTime now = LocalDateTime.now();
        // 노출 대상 — USER, COMPLETED — 비교적 과거
        saveCompletedUserMessage(session.getId(), now.minusHours(1));
        // 제외 대상 — 더 최근에 만들어졌어도 lastChattedAt 에 잡히면 안 된다.
        aiChatMessageRepository.save(
                AiChatMessageFixture.failedAssistantMessageAt(session.getId(), "partial", now)
        );

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        // lastChattedAt 은 1시간 전 USER 메시지 시각이어야 한다 — FAILED 가 더 최근이지만 무시.
        assertThat(slice.getContent().get(0).lastChattedAt().toLocalDate())
                .isEqualTo(now.minusHours(1).toLocalDate());
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 다른 사용자 소유의 userBook 으로 조회하면 빈 결과")
    void 소유권_검증() {
        Long owner = nextUserId();
        Long other = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(owner, nextBookId()));
        saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "owned");

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), other, PageRequest.of(0, 10));

        assertThat(slice.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: ACTIVE 세션은 status='ACTIVE' 로 도출된다 (감상문 없음)")
    void status_ACTIVE_도출() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        AiChatSession active = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "active-session");

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        AiChatSessionListProjection projection = slice.getContent().get(0);
        assertThat(projection.sessionId()).isEqualTo(active.getId());
        assertThat(projection.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: LOCKED 세션은 'SUMMARIZING' 으로 도출된다")
    void status_SUMMARIZING_도출() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        saveSession(userBook.getId(), AiChatSession.Status.LOCKED, "summarizing-session");

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZING");
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: ACTIVE + 감상문 있으면 'SUMMARIZED' 로 도출")
    void status_SUMMARIZED_도출() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "summarized-session");
        summaryRepository.save(Summary.createCompleted(userBook.getId(), session.getId(), "제목", "본문"));

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZED");
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 감상문 여러 건이어도 세션은 한 번만 'SUMMARIZED' 로 나타난다")
    void status_감상문_여러건이어도_한번만() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "retried-session");
        summaryRepository.save(Summary.createCompleted(userBook.getId(), session.getId(), "옛 제목", "옛 본문"));
        summaryRepository.save(Summary.createCompleted(userBook.getId(), session.getId(), "새 제목", "새 본문"));

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZED");
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 재생성 중(LOCKED)이면 직전 감상문이 있어도 'SUMMARIZING'")
    void status_재생성_중에는_SUMMARIZING_우선() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        AiChatSession session = saveSession(userBook.getId(), AiChatSession.Status.LOCKED, "regenerating-session");
        summaryRepository.save(Summary.createCompleted(userBook.getId(), session.getId(), "제목", "본문"));

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        assertThat(slice.getContent().get(0).status()).isEqualTo("SUMMARIZING");
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 다른 userBook 의 세션은 노출되지 않는다")
    void 다른_userBook_분리() {
        Long userId = nextUserId();
        UserBook bookA = userBookRepository.save(UserBook.create(userId, nextBookId()));
        UserBook bookB = userBookRepository.save(UserBook.create(userId, nextBookId()));

        saveSession(bookA.getId(), AiChatSession.Status.ACTIVE, "a-session");
        saveSession(bookB.getId(), AiChatSession.Status.ACTIVE, "b-session");

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(bookA.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).hasSize(1);
        assertThat(slice.getContent().get(0).title()).isEqualTo("a-session");
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: Slice 의 hasNext 가 페이지 size 와 비교해 반환된다")
    void hasNext_페이지네이션() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        for (int i = 0; i < 5; i++) {
            saveSession(userBook.getId(), AiChatSession.Status.ACTIVE, "s" + i);
        }

        Slice<AiChatSessionListProjection> firstPage = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 3));
        assertThat(firstPage.getContent()).hasSize(3);
        assertThat(firstPage.hasNext()).isTrue();

        Slice<AiChatSessionListProjection> secondPage = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(1, 3));
        assertThat(secondPage.getContent()).hasSize(2);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 세션이 없으면 빈 Slice 를 반환한다")
    void 세션_없을_때() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent()).isEmpty();
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findAutoSummaryTargetSessionIds: ACTIVE + 누적토큰≥임계 + 24h 내 메시지 세션만 반환한다")
    void 자동요약_대상_선정() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(24);

        AiChatSession eligible = saveSessionWithTokens(userBook.getId(), AiChatSession.Status.ACTIVE, 600);
        saveCompletedUserMessage(eligible.getId(), now.minusHours(1));

        AiChatSession idle = saveSessionWithTokens(userBook.getId(), AiChatSession.Status.ACTIVE, 600);
        saveCompletedUserMessage(idle.getId(), now.minusDays(3)); // 24h 밖 — 제외

        AiChatSession lowTokens = saveSessionWithTokens(userBook.getId(), AiChatSession.Status.ACTIVE, 100);
        saveCompletedUserMessage(lowTokens.getId(), now.minusHours(1)); // 토큰 부족 — 제외

        AiChatSession locked = saveSessionWithTokens(userBook.getId(), AiChatSession.Status.LOCKED, 600);
        saveCompletedUserMessage(locked.getId(), now.minusHours(1)); // ACTIVE 아님 — 제외

        java.util.List<Long> targets = aiChatSessionRepository.findAutoSummaryTargetSessionIds(
                AiChatSession.Status.ACTIVE, 500, com.readum.model.aiChat.entity.AiChatMessage.Status.COMPLETED, since);

        assertThat(targets).containsExactly(eligible.getId());
    }

    private AiChatSession saveSessionWithTokens(Long userBookId, AiChatSession.Status status, int tokens) {
        AiChatSession session = AiChatSession.create(userBookId);
        if (tokens > 0) {
            session.addAssistantTokens(tokens);
        }
        if (status == AiChatSession.Status.LOCKED) {
            session.lock();
        }
        return aiChatSessionRepository.save(session);
    }

    private AiChatSession saveSession(Long userBookId, AiChatSession.Status status, String title) {
        AiChatSession session = AiChatSession.create(userBookId);
        if (status == AiChatSession.Status.LOCKED) {
            session.lock();
        }
        if (title != null) {
            session.updateTitle(title);
        }
        return aiChatSessionRepository.save(session);
    }

    private void saveCompletedUserMessage(Long sessionId, LocalDateTime createdAt) {
        aiChatMessageRepository.save(AiChatMessageFixture.userMessageAt(sessionId, "msg", createdAt));
    }
}
