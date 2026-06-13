package com.readum.model.user.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.Book;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.entity.UserBookFixture;
import com.readum.model.user.repository.projection.UserBookListItemProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserBookRepositoryTest {

    @Autowired
    private UserBookRepository userBookRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;

    @Test
    @DisplayName("등록된 도서가 있으면 existsByUserId 가 true 를 반환한다")
    void existsByUserId_등록된_경우() {
        Long userId = nextUserId();
        Long bookId = nextBookId();
        userBookRepository.save(UserBook.create(userId, bookId));

        boolean exists = userBookRepository.existsByUserId(userId);

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("등록된 도서가 없으면 existsByUserId 가 false 를 반환한다")
    void existsByUserId_미등록_경우() {
        Long userId = nextUserId();

        boolean exists = userBookRepository.existsByUserId(userId);

        assertThat(exists).isFalse();
    }

    private static long userIdSeq = 800_000L;
    private static long bookIdSeq = 800_000L;

    @Test
    @DisplayName("동일한 userId 로 조회하면 present")
    void findByIdAndUserId_매칭시_present() {
        Long userId = nextUserId();
        Long bookId = 100L;

        UserBook saved = userBookRepository.save(UserBook.create(userId, bookId));

        Optional<UserBook> found = userBookRepository.findByIdAndUserId(saved.getId(), userId);

        assertThat(found).isPresent();
        assertThat(found.get().getBookId()).isEqualTo(bookId);
    }

    @Test
    @DisplayName("다른 userId 로 조회하면 empty")
    void findByIdAndUserId_userId_불일치시_empty() {
        Long userId = nextUserId();
        Long otherUserId = nextUserId();
        Long bookId = 100L;

        UserBook saved = userBookRepository.save(UserBook.create(userId, bookId));

        Optional<UserBook> found = userBookRepository.findByIdAndUserId(saved.getId(), otherUserId);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findAllByUserIdOrderByCreatedAtDescIdDesc 는 본인이 등록한 도서만 createdAt DESC, id DESC 순으로 Book 정보와 조인해 반환한다")
    void 등록도서_목록_조회_정렬과_조인() {
        Long userId = nextUserId();
        Long otherUserId = nextUserId();

        Book older = bookRepository.save(Book.create(
                uniqueExternalId(), "이전에 등록한 책", "저자A", "출판사A", 2024, "http://example.com/a.jpg"));
        Book newer = bookRepository.save(Book.create(
                uniqueExternalId(), "최근 등록한 책", "저자B", "출판사B", 2025, "http://example.com/b.jpg"));
        Book otherUserBook = bookRepository.save(Book.create(
                uniqueExternalId(), "남의 책", "저자C", "출판사C", 2023, "http://example.com/c.jpg"));

        LocalDateTime baseTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        UserBook olderUserBook = userBookRepository.save(
                UserBookFixture.userBookRegisteredAt(userId, older.getId(), baseTime));
        UserBook newerUserBook = userBookRepository.save(
                UserBookFixture.userBookRegisteredAt(userId, newer.getId(), baseTime.plusMinutes(1)));
        userBookRepository.save(
                UserBookFixture.userBookRegisteredAt(otherUserId, otherUserBook.getId(), baseTime.plusMinutes(2)));

        List<UserBookListItemProjection> projections =
                userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);

        assertThat(projections).hasSize(2);
        assertThat(projections.get(0).userBookId()).isEqualTo(newerUserBook.getId());
        assertThat(projections.get(0).bookId()).isEqualTo(newer.getId());
        assertThat(projections.get(0).title()).isEqualTo("최근 등록한 책");
        assertThat(projections.get(0).publisher()).isEqualTo("출판사B");
        assertThat(projections.get(0).publishedYear()).isEqualTo(2025);
        assertThat(projections.get(0).coverUrl()).isEqualTo("http://example.com/b.jpg");
        assertThat(projections.get(1).userBookId()).isEqualTo(olderUserBook.getId());
        assertThat(projections.get(1).bookId()).isEqualTo(older.getId());
        assertThat(projections.get(1).title()).isEqualTo("이전에 등록한 책");
        assertThat(projections)
                .extracting(UserBookListItemProjection::bookId)
                .doesNotContain(otherUserBook.getId());
    }

    @Test
    @DisplayName("findAllByUserIdOrderByCreatedAtDescIdDesc 는 createdAt 이 동일하면 user_book.id DESC 로 정렬한다")
    void 등록도서_목록_조회_createdAt_동일시_id_DESC() {
        Long userId = nextUserId();

        Book book1 = bookRepository.save(Book.create(
                uniqueExternalId(), "책1", "저자1", "출판사1", 2024, "http://example.com/1.jpg"));
        Book book2 = bookRepository.save(Book.create(
                uniqueExternalId(), "책2", "저자2", "출판사2", 2024, "http://example.com/2.jpg"));

        LocalDateTime sameTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        UserBook first = userBookRepository.save(
                UserBookFixture.userBookRegisteredAt(userId, book1.getId(), sameTime));
        UserBook second = userBookRepository.save(
                UserBookFixture.userBookRegisteredAt(userId, book2.getId(), sameTime));

        List<UserBookListItemProjection> projections =
                userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);

        assertThat(projections).hasSize(2);
        assertThat(second.getId()).isGreaterThan(first.getId());
        assertThat(projections.get(0).userBookId()).isEqualTo(second.getId());
        assertThat(projections.get(0).bookId()).isEqualTo(book2.getId());
        assertThat(projections.get(1).userBookId()).isEqualTo(first.getId());
        assertThat(projections.get(1).bookId()).isEqualTo(book1.getId());
    }

    @Test
    @DisplayName("등록한 도서가 없으면 빈 리스트를 반환한다")
    void 등록도서_없으면_빈_리스트() {
        Long userId = nextUserId();

        List<UserBookListItemProjection> projections =
                userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);

        assertThat(projections).isEmpty();
    }

    @Test
    @DisplayName("findByUserId 는 해당 사용자의 등록 도서만 전부 반환한다")
    void findByUserId_본인_등록_도서만_반환() {
        Long userId = nextUserId();
        Long otherUserId = nextUserId();
        UserBook first = userBookRepository.save(UserBook.create(userId, nextBookId()));
        UserBook second = userBookRepository.save(UserBook.create(userId, nextBookId()));
        userBookRepository.save(UserBook.create(otherUserId, nextBookId()));

        List<UserBook> found = userBookRepository.findByUserId(userId);

        assertThat(found).extracting(UserBook::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    @DisplayName("chatSessionCount 는 세션 상태(ACTIVE/CLOSED)와 무관하게 해당 도서의 전체 채팅 세션 수를 센다")
    void chatSessionCount_상태_무관_전체_세션_수를_센다() {
        Long userId = nextUserId();
        Book book = bookRepository.save(Book.create(
                uniqueExternalId(), "채팅한 책", "저자", "출판사", 2024, "http://example.com/x.jpg"));
        UserBook userBook = userBookRepository.save(UserBook.create(userId, book.getId()));

        persistActiveSession(userBook.getId());
        persistActiveSession(userBook.getId());
        persistClosedSession(userBook.getId());

        List<UserBookListItemProjection> projections =
                userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);

        assertThat(projections).hasSize(1);
        assertThat(projections.get(0).userBookId()).isEqualTo(userBook.getId());
        assertThat(projections.get(0).chatSessionCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("chatSessionCount 는 채팅 세션이 없는 도서에 대해 0 을 반환한다")
    void chatSessionCount_세션_없으면_0() {
        Long userId = nextUserId();
        Book book = bookRepository.save(Book.create(
                uniqueExternalId(), "대화 없는 책", "저자", "출판사", 2024, "http://example.com/y.jpg"));
        userBookRepository.save(UserBook.create(userId, book.getId()));

        List<UserBookListItemProjection> projections =
                userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);

        assertThat(projections).hasSize(1);
        assertThat(projections.get(0).chatSessionCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("chatSessionCount 는 다른 사용자의 userBook 에 달린 세션을 포함하지 않는다")
    void chatSessionCount_다른_사용자_세션_격리() {
        Long userId = nextUserId();
        Long otherUserId = nextUserId();

        Book myBook = bookRepository.save(Book.create(
                uniqueExternalId(), "내 책", "저자", "출판사", 2024, "http://example.com/m.jpg"));
        Book otherBook = bookRepository.save(Book.create(
                uniqueExternalId(), "남의 책", "저자", "출판사", 2023, "http://example.com/o.jpg"));

        UserBook myUserBook = userBookRepository.save(UserBook.create(userId, myBook.getId()));
        UserBook otherUserBook = userBookRepository.save(UserBook.create(otherUserId, otherBook.getId()));

        persistActiveSession(myUserBook.getId());
        persistActiveSession(otherUserBook.getId());
        persistActiveSession(otherUserBook.getId());

        List<UserBookListItemProjection> projections =
                userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);

        assertThat(projections).hasSize(1);
        assertThat(projections.get(0).userBookId()).isEqualTo(myUserBook.getId());
        assertThat(projections.get(0).chatSessionCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("chatSessionCount 는 한 사용자의 도서마다 자신의 세션 수를 독립적으로 세고, 기존 정렬을 유지한다")
    void chatSessionCount_도서별_독립_카운트와_정렬_유지() {
        Long userId = nextUserId();

        Book olderBook = bookRepository.save(Book.create(
                uniqueExternalId(), "이전에 등록한 책", "저자", "출판사", 2024, "http://example.com/a.jpg"));
        Book newerBook = bookRepository.save(Book.create(
                uniqueExternalId(), "최근 등록한 책", "저자", "출판사", 2025, "http://example.com/b.jpg"));

        LocalDateTime baseTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        UserBook olderUserBook = userBookRepository.save(
                UserBookFixture.userBookRegisteredAt(userId, olderBook.getId(), baseTime));
        UserBook newerUserBook = userBookRepository.save(
                UserBookFixture.userBookRegisteredAt(userId, newerBook.getId(), baseTime.plusMinutes(1)));

        persistActiveSession(newerUserBook.getId());
        persistActiveSession(newerUserBook.getId());

        List<UserBookListItemProjection> projections =
                userBookRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);

        assertThat(projections).hasSize(2);
        assertThat(projections.get(0).userBookId()).isEqualTo(newerUserBook.getId());
        assertThat(projections.get(0).chatSessionCount()).isEqualTo(2L);
        assertThat(projections.get(1).userBookId()).isEqualTo(olderUserBook.getId());
        assertThat(projections.get(1).chatSessionCount()).isEqualTo(0L);
    }

    private void persistActiveSession(Long userBookId) {
        aiChatSessionRepository.save(AiChatSession.create(userBookId));
    }

    private void persistClosedSession(Long userBookId) {
        AiChatSession session = AiChatSession.create(userBookId);
        session.close();
        aiChatSessionRepository.save(session);
    }

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }

    private static synchronized Long nextBookId() {
        bookIdSeq += 1;
        return bookIdSeq;
    }

    private static String uniqueExternalId() {
        return "ext-" + UUID.randomUUID();
    }
}
