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

    /** "지금 생성 중" 판정 — 유효 lease 의 PROCESSING 작업이 그 세션에 있는가. */
    @Query("""
            select case when count(summaryJob) > 0 then true else false end
              from SummaryJob summaryJob
             where summaryJob.aiChatSessionId = :sessionId
               and summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
               and summaryJob.lockedUntil > :now
            """)
    boolean existsActiveProcessingJob(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);

    /** 세션에 미완료(활성) 작업이 이미 있는지 — 적재 멱등성 사전 확인용. */
    boolean existsByActiveSessionId(Long activeSessionId);
}
