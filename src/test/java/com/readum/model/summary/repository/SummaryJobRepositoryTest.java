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
                        SummaryJob.Status.PENDING, now, PageRequest.of(0, 10));

        assertThat(claimable).extracting(SummaryJob::getId).contains(ready.getId());
        assertThat(claimable).allMatch(job -> !job.getNextAttemptAt().isAfter(now));
    }

    @Test
    void findOrphaned_은_lease가_만료된_PROCESSING을_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        SummaryJob expiredProcessing = summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, nextSessionId(), "dead", now.minusMinutes(1)));
        summaryJobRepository.save(
                SummaryJobFixture.persistedProcessing(null, nextSessionId(), "alive", now.plusMinutes(5)));

        List<SummaryJob> orphans = summaryJobRepository.findOrphaned(now, PageRequest.of(0, 10));

        assertThat(orphans).extracting(SummaryJob::getId)
                .contains(expiredProcessing.getId());
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
    void findClaimable_은_접수가_이른_순서로_정렬한다() {
        LocalDateTime now = LocalDateTime.now();
        long earlierSession = nextSessionId();
        long laterSession = nextSessionId();
        // 일부러 나중에 접수된 것을 먼저 저장해 정렬이 삽입순이 아님을 확인한다.
        summaryJobRepository.save(SummaryJobFixture.persistedPendingCreatedAt(
                null, laterSession, now.minusSeconds(10), now.minusMinutes(1)));
        summaryJobRepository.save(SummaryJobFixture.persistedPendingCreatedAt(
                null, earlierSession, now.minusSeconds(10), now.minusMinutes(30)));

        List<SummaryJob> claimable =
                summaryJobRepository.findClaimable(
                        SummaryJob.Status.PENDING, now, PageRequest.of(0, 10));

        List<Long> sessionOrder = claimable.stream().map(SummaryJob::getAiChatSessionId).toList();
        assertThat(sessionOrder.indexOf(earlierSession)).isLessThan(sessionOrder.indexOf(laterSession));
    }

    @Test
    void 공급자_차단으로_되돌아온_오래된_작업이_새로_접수된_작업보다_먼저_나온다() {
        // 무벌점 반납은 다음 시도 시각을 "지금" 으로 다시 찍는다. 시도 시각으로 정렬하면 그 작업이
        // 그 사이 접수된 새 작업보다 뒤로 밀려, 오래 기다린 사람이 더 오래 기다리게 된다.
        LocalDateTime now = LocalDateTime.now();
        long longWaiting = nextSessionId();
        long justArrived = nextSessionId();
        summaryJobRepository.save(SummaryJobFixture.persistedPendingCreatedAt(
                null, longWaiting, now, now.minusHours(3)));
        summaryJobRepository.save(SummaryJobFixture.persistedPendingCreatedAt(
                null, justArrived, now.minusSeconds(30), now.minusSeconds(30)));

        List<SummaryJob> claimable = summaryJobRepository.findClaimable(
                SummaryJob.Status.PENDING, now, PageRequest.of(0, 10));

        List<Long> sessionOrder = claimable.stream().map(SummaryJob::getAiChatSessionId).toList();
        assertThat(sessionOrder.indexOf(longWaiting)).isLessThan(sessionOrder.indexOf(justArrived));
    }

    @Test
    void findExpired_는_접수_기한을_넘긴_미완료_작업만_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiredBefore = now.minusHours(24);
        SummaryJob tooOld = summaryJobRepository.save(SummaryJobFixture.persistedPendingCreatedAt(
                null, nextSessionId(), now, now.minusHours(30)));
        SummaryJob recent = summaryJobRepository.save(SummaryJobFixture.persistedPendingCreatedAt(
                null, nextSessionId(), now, now.minusHours(1)));

        List<SummaryJob> expired = summaryJobRepository.findExpired(expiredBefore, PageRequest.of(0, 10));

        assertThat(expired).extracting(SummaryJob::getId).contains(tooOld.getId());
        assertThat(expired).extracting(SummaryJob::getId).doesNotContain(recent.getId());
    }

    @Test
    void existsUnfinishedJob_은_시도_시각이_미래인_작업도_남은_것으로_센다() {
        // 선점이 빈손으로 돌아왔다고 해서 적체가 비었다는 뜻은 아니다 — 백오프로 시도 시각이 미래인 작업이 남아 있다.
        LocalDateTime now = LocalDateTime.now();
        summaryJobRepository.save(SummaryJobFixture.persistedPendingCreatedAt(
                null, nextSessionId(), now.plusMinutes(10), now));

        assertThat(summaryJobRepository.existsUnfinishedJob()).isTrue();
        assertThat(summaryJobRepository.findClaimable(
                SummaryJob.Status.PENDING, now, PageRequest.of(0, 10))).isEmpty();
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
    void active_session_id_는_세션당_하나만_허용한다() {
        long sessionId = nextSessionId();
        summaryJobRepository.saveAndFlush(SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        summaryJobRepository.saveAndFlush(
                                SummaryJobFixture.persistedPending(null, sessionId, LocalDateTime.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
