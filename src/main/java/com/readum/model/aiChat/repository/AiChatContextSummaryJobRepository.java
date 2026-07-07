package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatContextSummaryJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiChatContextSummaryJobRepository extends JpaRepository<AiChatContextSummaryJob, Long> {

    /**
     * 처리 가능한 PENDING 작업을 선점 후보로 조회한다.
     * PESSIMISTIC_WRITE + lock timeout -2(Hibernate SKIP LOCKED): 다른 워커가 이미 잠근 행은 건너뛴다.
     * 주의: SKIP LOCKED 동시-skip 동작은 MySQL 에서 성립하며 H2 에서는 무시될 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select contextSummaryJob
              from AiChatContextSummaryJob contextSummaryJob
             where contextSummaryJob.status = :status
               and contextSummaryJob.nextAttemptAt <= :now
             order by contextSummaryJob.nextAttemptAt asc
                    , contextSummaryJob.id asc
            """)
    List<AiChatContextSummaryJob> findClaimable(
            @Param("status") AiChatContextSummaryJob.Status status,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /** lease 가 만료된 고아 작업을 조회한다. 회수기가 PENDING 으로 되돌릴 대상. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select contextSummaryJob
              from AiChatContextSummaryJob contextSummaryJob
             where contextSummaryJob.status = com.readum.model.aiChat.entity.AiChatContextSummaryJob.Status.PROCESSING
               and contextSummaryJob.lockedUntil < :now
             order by contextSummaryJob.lockedUntil asc
            """)
    List<AiChatContextSummaryJob> findOrphaned(@Param("now") LocalDateTime now, Pageable pageable);

    /** 기록/회수 시 행 단위 직렬화를 위한 비관적 락 조회. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select contextSummaryJob
              from AiChatContextSummaryJob contextSummaryJob
             where contextSummaryJob.id = :id
            """)
    Optional<AiChatContextSummaryJob> findByIdForUpdate(@Param("id") Long id);

    /** 세션에 미완료(활성) 작업이 이미 있는지 — 적재 멱등성 사전 확인용. */
    boolean existsByActiveSessionId(Long activeSessionId);

    /**
     * 등록 도서(UserBook) 삭제 cascade 용 — 그 도서의 세션들에 속한 작업을 일괄 삭제한다.
     * 세션(AiChatSession) 서브쿼리로 좁힌다. 세션이 먼저 삭제되면 서브쿼리가 비므로, 반드시 세션 삭제 전에 호출한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from AiChatContextSummaryJob contextSummaryJob
             where contextSummaryJob.sessionId in (
                   select aiChatSession.id
                     from AiChatSession aiChatSession
                    where aiChatSession.userBookId = :userBookId
                 )
            """)
    int deleteAllByUserBookId(@Param("userBookId") Long userBookId);
}
