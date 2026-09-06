package com.readum.model.summary.repository;

import com.readum.model.summary.entity.SummaryJobFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SummaryJobRepository#existsBlockingSummaryJob} 의 차단 판정 조건을 검증하는 DAO 통합 테스트.
 * 판정 기준:
 * - PENDING → 차단 (요청 시점 이후 메시지 끼어들기 방지)
 * - 유효 점유(lockedUntil > now) PROCESSING → 차단
 * - 점유 만료 PROCESSING → 차단 아님
 */
@SpringBootTest
@Transactional
class SummaryJobRepositoryBlockingTest {

    @Autowired
    private SummaryJobRepository repository;

    private static long sessionSeq = 800_000L;

    private static synchronized long nextSessionId() {
        return sessionSeq++;
    }

    @Test
    void PENDING_작업은_차단으로_본다() {
        long sessionId = nextSessionId();
        repository.save(SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now()));

        assertThat(repository.existsBlockingSummaryJob(sessionId, LocalDateTime.now())).isTrue();
    }

    @Test
    void 유효_점유_PROCESSING은_차단으로_본다() {
        long sessionId = nextSessionId();
        repository.save(SummaryJobFixture.persistedProcessing(
                null, sessionId, "o", LocalDateTime.now().plusMinutes(5)));

        assertThat(repository.existsBlockingSummaryJob(sessionId, LocalDateTime.now())).isTrue();
    }

    @Test
    void 점유_만료된_PROCESSING은_차단_아니다() {
        long sessionId = nextSessionId();
        repository.save(SummaryJobFixture.persistedProcessing(
                null, sessionId, "o", LocalDateTime.now().minusMinutes(1)));

        assertThat(repository.existsBlockingSummaryJob(sessionId, LocalDateTime.now())).isFalse();
    }
}
