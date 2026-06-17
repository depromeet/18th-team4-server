package com.readum.model.summary.repository;

import com.readum.model.summary.entity.SummaryJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SummaryJobRepository extends JpaRepository<SummaryJob, Long> {

    /**
     * 처리 가능한 PENDING 작업을 선점 후보로 조회한다.
     * executionMode 로 SYNC/BATCH 를 분리해 두 소비자가 서로의 큐를 침범하지 않는다.
     * PESSIMISTIC_WRITE + lock timeout -2(Hibernate SKIP LOCKED): 다른 워커가 이미 잠근 행은 건너뛴다.
     * 주의: SKIP LOCKED 동시-skip 동작은 MySQL 에서 성립하며 H2 에서는 무시될 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.executionMode = :executionMode
               and summaryJob.status = :status
               and summaryJob.nextAttemptAt <= :now
             order by summaryJob.nextAttemptAt asc
                    , summaryJob.id asc
            """)
    List<SummaryJob> findClaimable(
            @Param("executionMode") SummaryJob.ExecutionMode executionMode,
            @Param("status") SummaryJob.Status status,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /**
     * lease 가 만료된 고아 작업을 조회한다. 회수기가 PENDING 으로 되돌릴 대상.
     * PROCESSING(동기 워커 점유)과 BATCH_BUILDING(builder 청크 점유) 둘 다 포함한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.status in (
                       com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                     , com.readum.model.summary.entity.SummaryJob.Status.BATCH_BUILDING
                   )
               and summaryJob.lockedUntil < :now
             order by summaryJob.lockedUntil asc
            """)
    List<SummaryJob> findOrphaned(@Param("now") LocalDateTime now, Pageable pageable);

    /** 기록/회수 시 행 단위 직렬화를 위한 비관적 락 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.id = :id
            """)
    Optional<SummaryJob> findByIdForUpdate(@Param("id") Long id);

    /**
     * "지금 생성 중(차단)" 판정 — 그 세션에 아래 조건을 만족하는 작업이 하나라도 있는가.
     * - 수동(SYNC) 요청으로 대기 중인 PENDING: 수동은 "요청 시점 대화 내용"을 기준으로 생성하므로
     *   워커가 아직 작업을 집어가지 않은 시간에도 새 메시지가 끼어들면 안 된다 → SYNC+PENDING 은 차단.
     *   자동(BATCH) PENDING 은 builder 가 스냅샷을 찍는 BATCH_BUILDING 시점까지 메시지를 포함하는 설계이므로 차단하지 않는다.
     * - 유효 점유(lockedUntil > now) 상태의 PROCESSING 또는 BATCH_BUILDING
     * - 또는 SUBMITTED (배치에 제출되어 결과를 기다리는 중)
     */
    @Query("""
            select case when count(summaryJob) > 0 then true else false end
              from SummaryJob summaryJob
             where summaryJob.aiChatSessionId = :sessionId
               and (
                     (summaryJob.executionMode = com.readum.model.summary.entity.SummaryJob.ExecutionMode.SYNC
                      and summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PENDING)
                  or (summaryJob.status in (
                            com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                          , com.readum.model.summary.entity.SummaryJob.Status.BATCH_BUILDING
                      ) and summaryJob.lockedUntil > :now)
                  or summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.SUBMITTED
               )
            """)
    boolean existsBlockingSummaryJob(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);

    /**
     * BATCH 모드 PENDING 작업을 여러 건 선점 후보로 조회한다.
     * PESSIMISTIC_WRITE + lock timeout -2(Hibernate SKIP LOCKED): 다른 builder 가 이미 잠근 행은 건너뛴다.
     * maxJobsPerBatch 만큼을 한 번에 선점해 토큰 예산 청킹에 넘긴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.executionMode = com.readum.model.summary.entity.SummaryJob.ExecutionMode.BATCH
               and summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PENDING
               and summaryJob.nextAttemptAt <= :now
             order by summaryJob.nextAttemptAt asc
                    , summaryJob.id asc
            """)
    List<SummaryJob> findClaimableBatch(@Param("now") LocalDateTime now, Pageable pageable);

    /** 세션에 미완료(활성) 작업이 이미 있는지 — 적재 멱등성 사전 확인용. */
    boolean existsByActiveSessionId(Long activeSessionId);

    /** batch 전체 실패 시 해당 batch 에 묶인 SUBMITTED 작업 목록 조회 — 재큐 대상. */
    List<SummaryJob> findBySummaryBatchIdAndStatus(Long summaryBatchId, SummaryJob.Status status);
}
