package com.readum.model.user.repository;

import com.readum.model.user.entity.UserBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserBookRepositoryTest {

    @Autowired
    private UserBookRepository userBookRepository;

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

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }

    private static synchronized Long nextBookId() {
        bookIdSeq += 1;
        return bookIdSeq;
    }
}
