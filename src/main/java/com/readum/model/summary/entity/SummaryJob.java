package com.readum.model.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 감상문 생성 작업 큐의 한 행. "이 세션은 감상문을 만들어야 한다" 는 의도를 영속화한다.
 * 작업은 잃으면 복구 불가하므로 DB 에 영속한다.
 * 상태: PENDING(처리 대기) → PROCESSING(동기 워커 점유) / BATCH_BUILDING(builder 청크 점유)
 *   → SUBMITTED(OpenAI Batch 제출 완료) → SUCCEEDED / FAILED.
 * active_session_id: 미완료(PENDING/PROCESSING/BATCH_BUILDING/SUBMITTED) 동안만 세션 id, 완료/실패 시 NULL.
 *   → unique 제약으로 "세션당 활성 작업 1개" 를 보장한다.
 */
@Getter
@Entity
@Table(
        name = "summary_job",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_summary_job_active_session", columnNames = "active_session_id")
        },
        indexes = {
                @Index(name = "idx_summary_job_claim", columnList = "status, next_attempt_at"),
                @Index(name = "idx_summary_job_session", columnList = "ai_chat_session_id, status"),
                @Index(name = "idx_summary_job_mode_claim", columnList = "execution_mode, status, next_attempt_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class SummaryJob {

    public enum Status {
        PENDING, PROCESSING, BATCH_BUILDING, SUBMITTED, SUCCEEDED, FAILED
    }

    public enum ExecutionMode {
        SYNC, BATCH
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ai_chat_session_id", nullable = false)
    private Long aiChatSessionId;

    @Column(name = "active_session_id")
    private Long activeSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 10)
    private ExecutionMode executionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "lock_owner", length = 36)
    private String lockOwner;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "open_ai_batch_id")
    private Long openAiBatchId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 새 작업은 즉시 처리 가능한 PENDING 으로 시작한다. */
    public static SummaryJob createPending(Long aiChatSessionId, ExecutionMode executionMode) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                null, aiChatSessionId, aiChatSessionId, executionMode, Status.PENDING,
                null, null, null, 0, now, null, null, now, now
        );
    }

    /** 워커가 작업을 점유한다. owner 는 이 선점만의 토큰(UUID), lockedUntil 은 lease 만료 시각. */
    public void claim(String owner, LocalDateTime lockedUntil) {
        this.status = Status.PROCESSING;
        this.lockOwner = owner;
        this.lockedUntil = lockedUntil;
        this.updatedAt = LocalDateTime.now();
    }

    /** builder 가 청크로 점유한다 — BATCH_BUILDING. */
    public void startBatchBuilding(String owner, LocalDateTime lockedUntil) {
        this.status = Status.BATCH_BUILDING;
        this.lockOwner = owner;
        this.lockedUntil = lockedUntil;
        this.updatedAt = LocalDateTime.now();
    }

    /** OpenAI 제출 성공 — SUBMITTED. 점유 해제(워커가 들고 있지 않음), batch 연결. */
    public void markSubmitted(Long openAiBatchId) {
        this.status = Status.SUBMITTED;
        this.openAiBatchId = openAiBatchId;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isOwnedBy(String owner) {
        return (this.status == Status.PROCESSING || this.status == Status.BATCH_BUILDING)
                && owner != null && owner.equals(this.lockOwner);
    }

    public void markSucceeded() {
        this.status = Status.SUCCEEDED;
        this.activeSessionId = null;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** 일시 실패 — 백오프 후 다시 처리하도록 PENDING 으로. 활성(active_session_id) 은 유지. */
    public void scheduleRetry(LocalDateTime nextAttemptAt, String errorCode, String errorMessage) {
        this.status = Status.PENDING;
        this.attemptCount += 1;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.openAiBatchId = null;
        this.nextAttemptAt = nextAttemptAt;
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    /** 회복 불가 또는 시도 상한 초과 — 종료. 활성 해제로 다음 날 새 작업 적재를 허용. */
    public void markFailed(String errorCode, String errorMessage) {
        this.status = Status.FAILED;
        this.activeSessionId = null;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.openAiBatchId = null;
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    /** 회수기 전용 — lease 만료된 고아를 즉시 재선점 가능한 PENDING 으로 되돌린다. */
    public void releaseAfterOrphan(LocalDateTime now) {
        this.status = Status.PENDING;
        this.lockOwner = null;
        this.lockedUntil = null;
        this.openAiBatchId = null;
        this.nextAttemptAt = now;
        this.updatedAt = LocalDateTime.now();
    }

}
