package com.readum.model.summary.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryJobTransitionTest {

    @Test
    void BATCH_작업은_BATCH_모드_PENDING으로_생성된다() {
        SummaryJob job = SummaryJob.createPending(7L, SummaryJob.ExecutionMode.BATCH);
        assertThat(job.getExecutionMode()).isEqualTo(SummaryJob.ExecutionMode.BATCH);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(job.getActiveSessionId()).isEqualTo(7L);
    }

    @Test
    void 빌드_점유는_BATCH_BUILDING으로_owner와_점유시한을_설정한다() {
        SummaryJob job = SummaryJob.createPending(7L, SummaryJob.ExecutionMode.BATCH);
        LocalDateTime until = LocalDateTime.now().plusMinutes(5);
        job.startBatchBuilding("owner-1", until);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.BATCH_BUILDING);
        assertThat(job.isOwnedBy("owner-1")).isTrue();
        assertThat(job.getLockedUntil()).isEqualTo(until);
    }

    @Test
    void 제출은_SUBMITTED로_batchId를_설정하고_점유를_해제한다() {
        SummaryJob job = SummaryJob.createPending(7L, SummaryJob.ExecutionMode.BATCH);
        job.startBatchBuilding("owner-1", LocalDateTime.now().plusMinutes(5));
        job.markSubmitted(42L);
        assertThat(job.getStatus()).isEqualTo(SummaryJob.Status.SUBMITTED);
        assertThat(job.getOpenAiBatchId()).isEqualTo(42L);
        assertThat(job.getLockOwner()).isNull();
        assertThat(job.getLockedUntil()).isNull();
        assertThat(job.isOwnedBy("owner-1")).isFalse();
    }
}
