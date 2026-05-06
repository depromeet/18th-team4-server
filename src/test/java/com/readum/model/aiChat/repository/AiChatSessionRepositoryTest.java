package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
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
    private UserBookRepository userBookRepository;

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
    @DisplayName("findSessionsByUserBookIdAndOwner: updatedAt DESC, id DESC 로 정렬되어 반환된다")
    void 정렬_검증() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));

        AiChatSession older = save(userBook.getId(), AiChatSession.Status.ACTIVE, "older");
        AiChatSession middle = save(userBook.getId(), AiChatSession.Status.ACTIVE, "middle");
        AiChatSession newer = save(userBook.getId(), AiChatSession.Status.ACTIVE, "newer");

        // updatedAt 강제 설정으로 정렬 순서 결정
        forceUpdatedAt(older, LocalDateTime.now().minusMinutes(10));
        forceUpdatedAt(middle, LocalDateTime.now().minusMinutes(5));
        forceUpdatedAt(newer, LocalDateTime.now());

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent())
                .extracting(AiChatSessionListProjection::sessionId)
                .containsExactly(newer.getId(), middle.getId(), older.getId());
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 다른 사용자 소유의 userBook 으로 조회하면 빈 결과")
    void 소유권_검증() {
        Long owner = nextUserId();
        Long other = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(owner, nextBookId()));
        save(userBook.getId(), AiChatSession.Status.ACTIVE, "owned");

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), other, PageRequest.of(0, 10));

        assertThat(slice.getContent()).isEmpty();
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: ACTIVE / SUMMARIZING / CLOSED 모두 반환되며 status 가 그대로 매핑된다")
    void 상태_매핑_검증() {
        Long userId = nextUserId();
        UserBook userBook = userBookRepository.save(UserBook.create(userId, nextBookId()));

        save(userBook.getId(), AiChatSession.Status.ACTIVE, "active");
        save(userBook.getId(), AiChatSession.Status.SUMMARIZING, "summarizing");
        save(userBook.getId(), AiChatSession.Status.CLOSED, "closed");

        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository
                .findSessionsByUserBookIdAndOwner(userBook.getId(), userId, PageRequest.of(0, 10));

        assertThat(slice.getContent())
                .extracting(AiChatSessionListProjection::status)
                .containsExactlyInAnyOrder(
                        AiChatSession.Status.ACTIVE,
                        AiChatSession.Status.SUMMARIZING,
                        AiChatSession.Status.CLOSED
                );
    }

    @Test
    @DisplayName("findSessionsByUserBookIdAndOwner: 다른 userBook 의 세션은 노출되지 않는다")
    void 다른_userBook_분리() {
        Long userId = nextUserId();
        UserBook bookA = userBookRepository.save(UserBook.create(userId, nextBookId()));
        UserBook bookB = userBookRepository.save(UserBook.create(userId, nextBookId()));

        save(bookA.getId(), AiChatSession.Status.ACTIVE, "a-session");
        save(bookB.getId(), AiChatSession.Status.ACTIVE, "b-session");

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
            save(userBook.getId(), AiChatSession.Status.ACTIVE, "s" + i);
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

    private AiChatSession save(Long userBookId, AiChatSession.Status status, String title) {
        AiChatSession session = AiChatSession.create(userBookId);
        if (status != AiChatSession.Status.ACTIVE) {
            applyStatus(session, status);
        }
        if (title != null) {
            session.updateTitle(title);
        }
        return aiChatSessionRepository.save(session);
    }

    private void applyStatus(AiChatSession session, AiChatSession.Status status) {
        switch (status) {
            case SUMMARIZING -> session.markSummarizing();
            case CLOSED -> session.close();
            case ACTIVE -> {
                // 기본 상태이므로 변경 불필요
            }
        }
    }

    /**
     * SpringBootTest 의 영속성 컨텍스트 안에서 entity 의 updatedAt 을 강제 갱신.
     * 운영 코드에서는 appendUserMessage / addAssistantTokens / markSummarizing 등이 갱신하지만,
     * 테스트에서는 정렬 검증을 위해 직접 시각을 주입한다. JPA dirty-checking 을 활용하기 위해
     * AllArgsConstructor 의 of() 로 새로 만들어 동일 id 로 save (merge) 한다.
     */
    private void forceUpdatedAt(AiChatSession session, LocalDateTime updatedAt) {
        AiChatSession replaced = AiChatSession.of(
                session.getId(),
                session.getUserBookId(),
                session.getStatus(),
                session.getUserMessageCount(),
                session.getAccumulatedTokens(),
                session.getTitle(),
                session.getCreatedAt(),
                updatedAt
        );
        aiChatSessionRepository.saveAndFlush(replaced);
    }

}
