package com.readum.model.summary.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link SummaryJob} 을 만드는 명명 팩토리. 같은 패키지의 package-private 전체필드 생성자 호출.
 */
@TestOnly
public final class SummaryJobFixture {

    private SummaryJobFixture() {
    }

    /** 저장되어 처리 대기(PENDING)인 SYNC 작업. nextAttemptAt 으로 처리 가능 시점을 제어한다. */
    public static SummaryJob persistedPending(Long id, Long sessionId, LocalDateTime nextAttemptAt) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.ExecutionMode.SYNC, SummaryJob.Status.PENDING,
                null, null, null, 0, nextAttemptAt, null, null, now, now
        );
    }

    /** 저장되어 점유(PROCESSING)된 SYNC 작업. lockedUntil 로 lease 만료 여부를 제어한다(회수기 테스트용). */
    public static SummaryJob persistedProcessing(
            Long id, Long sessionId, String owner, LocalDateTime lockedUntil
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.ExecutionMode.SYNC, SummaryJob.Status.PROCESSING,
                owner, lockedUntil, null, 0, now, null, null, now, now
        );
    }

    /** BATCH 모드 처리 대기. builder 선점 테스트용. */
    public static SummaryJob persistedBatchPending(Long id, Long sessionId, LocalDateTime nextAttemptAt) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.ExecutionMode.BATCH, SummaryJob.Status.PENDING,
                null, null, null, 0, nextAttemptAt, null, null, now, now
        );
    }

    /** BATCH_BUILDING 점유 작업. 회수기 테스트용(lockedUntil 로 만료 제어). */
    public static SummaryJob persistedBatchBuilding(
            Long id, Long sessionId, String owner, LocalDateTime lockedUntil
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.ExecutionMode.BATCH, SummaryJob.Status.BATCH_BUILDING,
                owner, lockedUntil, null, 0, now, null, null, now, now
        );
    }

    /** SUBMITTED 작업(점유 시한 없음). collector·미회수 테스트용. summaryBatchId 연결. */
    public static SummaryJob persistedSubmitted(Long id, Long sessionId, Long summaryBatchId) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.ExecutionMode.BATCH, SummaryJob.Status.SUBMITTED,
                null, null, summaryBatchId, 0, now, null, null, now, now
        );
    }

    /**
     * 이미 처리 완료(SUCCEEDED)된 BATCH 작업. collector 멱등성 테스트용 —
     * "수집 완료 후 재수집해도 작업을 다시 건드리지 않는다" 시나리오의 준비 상태를 표현한다.
     * activeSessionId=null: 완료 시 활성 해제됨.
     */
    public static SummaryJob persistedSucceeded(Long id, Long sessionId) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, null, SummaryJob.ExecutionMode.BATCH, SummaryJob.Status.SUCCEEDED,
                null, null, null, 1, now, null, null, now, now
        );
    }
}
