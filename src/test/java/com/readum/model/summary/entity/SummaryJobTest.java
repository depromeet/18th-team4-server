package com.readum.model.summary.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryJobTest {

    @Test
    void createPending_은_PENDING_활성작업으로_시작한다() {
        SummaryJob job = SummaryJob.createPending(42L, SummaryJob.ExecutionMode.SYNC);

        assertThat(job.getAiChatSessionId()).isEqualTo(42L);
        assertThat(job.getActiveSessionId()).isEqualTo(42L);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
    }

    @Test
    void claim_은_PROCESSING_으로_바뀌고_소유자와_lease를_설정한다() {
        SummaryJob job = SummaryJob.createPending(1L, SummaryJob.ExecutionMode.SYNC);
        LocalDateTime until = LocalDateTime.now().plusMinutes(5);

        job.claim("owner-1", until);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PROCESSING);
        assertThat(job.getLockOwner()).isEqualTo("owner-1");
        assertThat(job.getLockedUntil()).isEqualTo(until);
        assertThat(job.getActiveSessionId()).isEqualTo(1L);
        assertThat(job.isOwnedBy("owner-1")).isTrue();
        assertThat(job.isOwnedBy("owner-2")).isFalse();
        assertThat(job.isOwnedBy(null)).isFalse();
    }

    @Test
    void markSucceeded_는_활성해제하고_SUCCEEDED로_만든다() {
        SummaryJob job = SummaryJob.createPending(1L, SummaryJob.ExecutionMode.BATCH);
        job.startBatchBuilding("owner-1", LocalDateTime.now().plusMinutes(5));
        job.markSubmitted(77L);  // openAiBatchId=77 설정 후 성공 처리 — 완료 행은 깨끗해야 한다

        job.markSucceeded();

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUCCEEDED);
        assertThat(job.getActiveSessionId()).isNull();
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
        assertThat(job.getOpenAiBatchId()).isNull();
    }

    @Test
    void scheduleRetry_는_시도횟수를_올리고_PENDING으로_되돌린다() {
        SummaryJob job = SummaryJob.createPending(1L, SummaryJob.ExecutionMode.SYNC);
        job.claim("owner-1", LocalDateTime.now().plusMinutes(5));
        LocalDateTime next = LocalDateTime.now().plusMinutes(1);

        job.scheduleRetry(next, "AI_PROVIDER_TRANSIENT", "일시 오류");

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(next);
        assertThat(job.getActiveSessionId()).isEqualTo(1L);
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLastErrorCode()).isEqualTo("AI_PROVIDER_TRANSIENT");
    }

    @Test
    void markFailed_는_활성해제하고_FAILED로_만든다() {
        SummaryJob job = SummaryJob.createPending(1L, SummaryJob.ExecutionMode.SYNC);
        job.claim("owner-1", LocalDateTime.now().plusMinutes(5));

        job.markFailed("AI_PROVIDER_ERROR", "회복 불가");

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.FAILED);
        assertThat(job.getActiveSessionId()).isNull();
        assertThat(job.getLastErrorMessage()).isEqualTo("회복 불가");
        assertThat(job.getLastErrorCode()).isEqualTo("AI_PROVIDER_ERROR");
    }

    @Test
    void releaseAfterOrphan_은_즉시_재선점가능한_PENDING으로_되돌린다() {
        SummaryJob job = SummaryJob.createPending(1L, SummaryJob.ExecutionMode.SYNC);
        job.claim("owner-1", LocalDateTime.now().minusMinutes(1));

        LocalDateTime releaseTime = LocalDateTime.now();
        job.releaseAfterOrphan(releaseTime);

        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
        assertThat(job.getActiveSessionId()).isEqualTo(1L);
        assertThat(job.getNextAttemptAt()).isEqualTo(releaseTime);
    }

}
