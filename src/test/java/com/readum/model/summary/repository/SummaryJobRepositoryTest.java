package com.readum.model.summary.repository;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SummaryJobRepositoryTest {

    @Autowired
    private SummaryJobRepository summaryJobRepository;

    private static long sessionSeq = 700_000L;

    private static synchronized long nextSessionId() {
        return sessionSeq++;
    }

    @Test
    void findClaimable_은_처리시점이_지난_PENDING만_nextAttemptAt_오름차순으로_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        SummaryJob ready = summaryJobRepository.save(
                SummaryJobFixture.persistedPending(null, nextSessionId(), now.minusSeconds(10)));
        summaryJobRepository.save(
                SummaryJobFixture.persistedPending(null, nextSessionId(), now.plusMinutes(10)));

        List<SummaryJob> claimable =
                summaryJobRepository.findClaimable(
                        SummaryJob.ExecutionMode.SYNC, SummaryJob.Status.PENDING, now, PageRequest.of(0, 10));

        assertThat(claimable).extracting(SummaryJob::getId).contains(ready.getId());
        assertThat(claimable).allMatch(job -> !job.getNextAttemptAt().isAfter(now));
    }

    @Test
    void findOrphaned_은_lease가_만료된_PROCESSING과_BATCH_BUILDING을_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        SummaryJob expiredProcessing = summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, nextSessionId(), "dead", now.minusMinutes(1)));
        SummaryJob expiredBatchBuilding = summaryJobRepository.save(
                SummaryJobFixture.persistedBatchBuilding(null, nextSessionId(), "dead-batch", now.minusMinutes(1)));
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, nextSessionId(), "alive", now.plusMinutes(5)));
        summaryJobRepository.save(
                SummaryJobFixture.persistedBatchBuilding(null, nextSessionId(), "alive-batch", now.plusMinutes(5)));

        List<SummaryJob> orphans = summaryJobRepository.findOrphaned(now, PageRequest.of(0, 10));

        assertThat(orphans).extracting(SummaryJob::getId)
                .contains(expiredProcessing.getId(), expiredBatchBuilding.getId());
        assertThat(orphans).allMatch(job -> job.getLockedUntil().isBefore(now));
    }

    @Test
    void existsBlockingSummaryJob_은_유효_lease의_PROCESSING이_있을때_true() {
        LocalDateTime now = LocalDateTime.now();
        long withValid = nextSessionId();
        long withExpired = nextSessionId();
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, withValid, "w", now.plusMinutes(5)));
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, withExpired, "w", now.minusMinutes(1)));

        assertThat(summaryJobRepository.existsBlockingSummaryJob(withValid, now)).isTrue();
        assertThat(summaryJobRepository.existsBlockingSummaryJob(withExpired, now)).isFalse();
        assertThat(summaryJobRepository.existsBlockingSummaryJob(nextSessionId(), now)).isFalse();
    }

    @Test
    void existsByActiveSessionId_는_미완료_작업이_있을때_true_완료_후_false() {
        long sessionId = nextSessionId();
        SummaryJob job = summaryJobRepository.save(
                SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now()));

        assertThat(summaryJobRepository.existsByActiveSessionId(sessionId)).isTrue();

        job.markSucceeded();
        summaryJobRepository.save(job);
        assertThat(summaryJobRepository.existsByActiveSessionId(sessionId)).isFalse();
    }

    @Test
    void findClaimable_은_nextAttemptAt_빠른순으로_정렬한다() {
        LocalDateTime now = LocalDateTime.now();
        long earlierSession = nextSessionId();
        long laterSession = nextSessionId();
        // 일부러 늦은 것을 먼저 저장해 정렬이 삽입순이 아님을 확인
        summaryJobRepository.save(SummaryJobFixture.persistedPending(null, laterSession, now.minusSeconds(10)));
        summaryJobRepository.save(SummaryJobFixture.persistedPending(null, earlierSession, now.minusSeconds(60)));

        List<SummaryJob> claimable =
                summaryJobRepository.findClaimable(
                        SummaryJob.ExecutionMode.SYNC, SummaryJob.Status.PENDING, now, PageRequest.of(0, 10));

        List<Long> sessionOrder = claimable.stream().map(SummaryJob::getAiChatSessionId).toList();
        assertThat(sessionOrder.indexOf(earlierSession)).isLessThan(sessionOrder.indexOf(laterSession));
    }

    @Test
    void findOrphaned_은_lease가_아직_안지난_경계는_제외한다() {
        LocalDateTime now = LocalDateTime.now();
        long sessionId = nextSessionId();
        // lockedUntil 이 now 이후(아직 유효) — 고아 아님
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, sessionId, "alive", now.plusSeconds(1)));

        List<SummaryJob> orphans = summaryJobRepository.findOrphaned(now, PageRequest.of(0, 10));

        assertThat(orphans).extracting(SummaryJob::getAiChatSessionId).doesNotContain(sessionId);
    }

    @Test
    void findOrphaned_는_SUBMITTED_작업을_회수_대상에_포함하지_않는다() {
        long sessionId = nextSessionId();
        SummaryJob submitted = summaryJobRepository.save(
                SummaryJobFixture.persistedSubmitted(null, sessionId, 1L));

        List<SummaryJob> orphans = summaryJobRepository.findOrphaned(
                LocalDateTime.now(), PageRequest.of(0, 10));

        assertThat(orphans).extracting(SummaryJob::getId).doesNotContain(submitted.getId());
    }

    @Test
    void existsByActiveSessionId_는_SUBMITTED_BATCH_작업이_있으면_true() {
        long sessionId = nextSessionId();
        summaryJobRepository.save(SummaryJobFixture.persistedSubmitted(null, sessionId, 1L));

        assertThat(summaryJobRepository.existsByActiveSessionId(sessionId)).isTrue();
    }

    @Test
    void active_session_id_는_세션당_하나만_허용한다() {
        long sessionId = nextSessionId();
        summaryJobRepository.saveAndFlush(SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        summaryJobRepository.saveAndFlush(
                                SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
