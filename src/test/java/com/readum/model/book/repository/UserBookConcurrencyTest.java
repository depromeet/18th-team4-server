package com.readum.model.book.repository;

import com.readum.domain.exception.ConflictException;
import com.readum.domain.userbook.dto.UserBookCreateCommand;
import com.readum.domain.userbook.dto.UserBookCreateResult;
import com.readum.domain.userbook.service.UserBookCreateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserBookConcurrencyTest {

    @Autowired
    private UserBookCreateService userBookCreateService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long USER_ID = 999_999L;
    private static final String EXTERNAL_ID = "concurrent-race-condition-test-isbn";

    @BeforeEach
    @AfterEach
    void cleanUpTestData() {
        jdbcTemplate.update("DELETE FROM user_book WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM book WHERE external_id = ?", EXTERNAL_ID);
    }

    @Test
    void 동일_도서를_10개_스레드가_동시에_등록_시도하면_Book은_1건_UserBook도_1건만_생성된다() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Object> results = new CopyOnWriteArrayList<>();

        UserBookCreateCommand command = new UserBookCreateCommand(
                USER_ID, EXTERNAL_ID, "동시성 테스트 책", "저자", "출판사", 2024,
                "http://example.com/cover.jpg"
        );

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
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

        startLatch.countDown(); // 모든 스레드 동시 출발
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("10초 내 모든 스레드가 완료되어야 한다").isTrue();
        assertThat(results).hasSize(threadCount);

        // 검증 1: BOOK 테이블에 해당 externalId를 가진 레코드가 정확히 1건
        long bookCount = bookRepository.findAll().stream()
                .filter(b -> EXTERNAL_ID.equals(b.getExternalId()))
                .count();
        assertThat(bookCount).as("Book 마스터는 1건만 존재해야 한다").isEqualTo(1L);

        // 검증 2: UnexpectedRollbackException 발생 없음
        long unexpectedRollbackCount = results.stream()
                .filter(r -> r instanceof UnexpectedRollbackException)
                .count();
        assertThat(unexpectedRollbackCount).as("UnexpectedRollbackException이 발생하면 안 된다").isZero();

        // 검증 3: 성공은 정확히 1건, 나머지 9건은 ConflictException 또는 DataIntegrityViolationException
        long successCount = results.stream()
                .filter(r -> r instanceof UserBookCreateResult)
                .count();
        assertThat(successCount).as("정확히 1건만 성공해야 한다").isEqualTo(1L);

        long conflictOrConstraintCount = results.stream()
                .filter(r -> r instanceof ConflictException || r instanceof DataIntegrityViolationException)
                .count();
        assertThat(conflictOrConstraintCount)
                .as("나머지 9건은 ConflictException 또는 DataIntegrityViolationException이어야 한다")
                .isEqualTo(9L);
    }
}
