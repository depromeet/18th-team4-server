package com.readum.model.userBook.repository;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.out.BookLookupClient;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.user.userbook.dto.UserBookCreateCommand;
import com.readum.domain.user.userbook.dto.UserBookCreateResult;
import com.readum.domain.user.userbook.service.UserBookCreateService;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@SpringBootTest
class UserBookConcurrencyTest {

    @Autowired
    private UserBookCreateService userBookCreateService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private BookLookupClient bookLookupClient;

    private static final String EXTERNAL_ID = "concurrent-race-condition-test-isbn";

    private Long userId;

    @BeforeEach
    void setUp() {
        given(bookLookupClient.execute(EXTERNAL_ID)).willReturn(
                new BookResult("http://example.com/cover.jpg", "동시성 테스트 책", "저자", "출판사", 2024, EXTERNAL_ID)
        );
        // 인증된 userId 를 명령 인자로 쓴다 — UserRepository 로 User row 를 만들고 그 id 를 넘긴다.
        // session_id unique 제약을 회피하기 위해 매 실행마다 UUID 로 다른 값을 쓴다.
        User saved = userRepository.save(User.create(UUID.randomUUID(), "책읽는여우"));
        userId = saved.getId();
    }

    @AfterEach
    void cleanUpTestData() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM user_book WHERE user_id = ?", userId);
            userRepository.deleteById(userId);
        }
        jdbcTemplate.update("DELETE FROM book WHERE external_id = ?", EXTERNAL_ID);
    }

    @Test
    void 동일_도서를_10개_스레드가_동시에_등록_시도하면_Book은_1건_UserBook도_1건만_생성된다() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Object> results = new CopyOnWriteArrayList<>();

        UserBookCreateCommand command = new UserBookCreateCommand(userId, EXTERNAL_ID);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (Thread.currentThread().isInterrupted()) return;
                    UserBookCreateResult result = userBookCreateService.execute(command);
                    results.add(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    results.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);

        try {
            assertThat(finished).as("10초 내 모든 스레드가 완료되어야 한다").isTrue();
            assertThat(results).hasSize(threadCount);

            Long bookCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM book WHERE external_id = ?", Long.class, EXTERNAL_ID);
            assertThat(bookCount).as("Book 마스터는 1건만 존재해야 한다").isEqualTo(1L);

            Long bookId = bookRepository.findByExternalId(EXTERNAL_ID)
                    .orElseThrow()
                    .getId();
            Long userBookCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_book WHERE user_id = ? AND book_id = ?",
                    Long.class, userId, bookId);
            assertThat(userBookCount).as("UserBook은 1건만 존재해야 한다").isEqualTo(1L);

            long unexpectedRollbackCount = results.stream()
                    .filter(r -> r instanceof UnexpectedRollbackException)
                    .count();
            assertThat(unexpectedRollbackCount).as("UnexpectedRollbackException이 발생하면 안 된다").isZero();

            long successCount = results.stream()
                    .filter(r -> r instanceof UserBookCreateResult)
                    .count();
            assertThat(successCount).as("정확히 1건만 성공해야 한다").isEqualTo(1L);

            long conflictCount = results.stream()
                    .filter(r -> r instanceof ConflictException)
                    .count();
            assertThat(conflictCount)
                    .as("나머지 9건은 모두 ConflictException이어야 한다")
                    .isEqualTo(9L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
