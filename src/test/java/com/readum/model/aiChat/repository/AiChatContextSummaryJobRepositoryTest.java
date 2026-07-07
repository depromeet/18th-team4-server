package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatContextSummaryJob;
import com.readum.model.aiChat.entity.AiChatContextSummaryJobFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AiChatContextSummaryJobRepositoryTest {

    @Autowired
    private AiChatContextSummaryJobRepository jobRepository;

    private static long sessionSeq = 800_000L;

    private static synchronized long nextSessionId() {
        return sessionSeq++;
    }

    @Test
    void findClaimable_은_처리시점이_지난_PENDING만_nextAttemptAt_오름차순으로_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        AiChatContextSummaryJob ready = jobRepository.save(
                AiChatContextSummaryJobFixture.persistedPendingDueAt(null, nextSessionId(), now.minusSeconds(10)));
        jobRepository.save(
                AiChatContextSummaryJobFixture.persistedPendingDueAt(null, nextSessionId(), now.plusMinutes(10)));

        List<AiChatContextSummaryJob> claimable = jobRepository.findClaimable(
                AiChatContextSummaryJob.Status.PENDING, now, PageRequest.of(0, 10));

        assertThat(claimable).extracting(AiChatContextSummaryJob::getId).contains(ready.getId());
        assertThat(claimable).allMatch(job -> !job.getNextAttemptAt().isAfter(now));
    }

    @Test
    void findOrphaned_은_lease가_만료된_PROCESSING을_가져오고_유효한_lease는_제외한다() {
        LocalDateTime now = LocalDateTime.now();
        AiChatContextSummaryJob expired = jobRepository.save(
                AiChatContextSummaryJobFixture.persistedProcessing(null, nextSessionId(), "dead", now.minusMinutes(1), 0));
        long aliveSession = nextSessionId();
        jobRepository.save(
                AiChatContextSummaryJobFixture.persistedProcessing(null, aliveSession, "alive", now.plusMinutes(5), 0));

        List<AiChatContextSummaryJob> orphans = jobRepository.findOrphaned(now, PageRequest.of(0, 10));

        assertThat(orphans).extracting(AiChatContextSummaryJob::getId).contains(expired.getId());
        assertThat(orphans).extracting(AiChatContextSummaryJob::getSessionId).doesNotContain(aliveSession);
    }

    @Test
    void existsByActiveSessionId_는_미완료_작업이_있을때_true_완료_후_false() {
        long sessionId = nextSessionId();
        AiChatContextSummaryJob job = jobRepository.save(
                AiChatContextSummaryJobFixture.persistedPending(null, sessionId));

        assertThat(jobRepository.existsByActiveSessionId(sessionId)).isTrue();

        job.markSucceeded();
        jobRepository.save(job);
        assertThat(jobRepository.existsByActiveSessionId(sessionId)).isFalse();
    }

    @Test
    void active_session_id_는_세션당_하나만_허용한다() {
        long sessionId = nextSessionId();
        jobRepository.saveAndFlush(AiChatContextSummaryJobFixture.persistedPending(null, sessionId));

        assertThatThrownBy(() ->
                jobRepository.saveAndFlush(AiChatContextSummaryJobFixture.persistedPending(null, sessionId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
