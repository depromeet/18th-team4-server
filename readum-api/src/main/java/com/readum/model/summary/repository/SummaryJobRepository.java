package com.readum.model.summary.repository;

import com.readum.model.summary.entity.SummaryJob;
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

public interface SummaryJobRepository extends JpaRepository<SummaryJob, Long> {

    /**
     * 처리 가능한 PENDING 작업을 선점 후보로 조회한다.
     * PESSIMISTIC_WRITE + lock timeout -2(Hibernate SKIP LOCKED): 다른 워커가 이미 잠근 행은 건너뛴다.
     * 주의: SKIP LOCKED 동시-skip 동작은 MySQL 에서 성립하며 H2 에서는 무시될 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.status = :status
               and summaryJob.nextAttemptAt <= :now
             order by summaryJob.nextAttemptAt asc
                    , summaryJob.id asc
            """)
    List<SummaryJob> findClaimable(
            @Param("status") SummaryJob.Status status,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /**
     * lease 가 만료된 고아 작업을 조회한다. 회수기가 PENDING 으로 되돌릴 대상.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select summaryJob
              from SummaryJob summaryJob
             where summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
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
     * - 대기 중인 PENDING: 생성은 "요청 시점 대화 내용"을 기준으로 하므로
     *   워커가 아직 작업을 집어가지 않은 시간에도 새 메시지가 끼어들면 안 된다 → PENDING 은 차단.
     * - 유효 점유(lockedUntil > now) 상태의 PROCESSING
     */
    @Query("""
            select case when count(summaryJob) > 0 then true else false end
              from SummaryJob summaryJob
             where summaryJob.aiChatSessionId = :sessionId
               and (
                     summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PENDING
                  or (summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                      and summaryJob.lockedUntil > :now)
               )
            """)
    boolean existsBlockingSummaryJob(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);

    /** 세션에 미완료(활성) 작업이 이미 있는지 — 적재 멱등성 사전 확인용. */
    boolean existsByActiveSessionId(Long activeSessionId);

    /**
     * 자동 요약 대상 세션들의 작업을 집합 단위 단일 INSERT 로 한 번에 적재한다.
     * 새벽 6시 적재를 세션마다 도는 per-row 루프(≈2N 쿼리/N 트랜잭션) 대신 1 쿼리/1 트랜잭션으로 줄인다.
     *
     * <p>대상 조건은 {@code AiChatSessionRepository.findAutoSummaryTargetSessionIds} 와 동일하게 맞춘다:
     * ACTIVE + 누적 토큰 ≥ minTokens + since 이후 COMPLETED 메시지 존재.
     * {@code NOT EXISTS(활성 작업)} 으로 중복 적재를 거르고(멱등), 그 사이 동시 적재(수동/타 인스턴스)가
     * 먼저 행을 넣은 드문 경합은 active_session_id unique 제약 + {@code INSERT IGNORE} 가 충돌 행만 건너뛰며 흡수한다.
     *
     * <p>id 가 IDENTITY 라 JPQL/HQL bulk-insert 가 안 되므로 네이티브 SQL. 컬럼 값은 {@code createPending}
     * 의 기본값(status='PENDING', attempt_count=0, 시각=:now)과 일치한다.
     *
     * @return 실제 적재된(insert 된) 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert ignore into summary_job
                    ( ai_chat_session_id
                    , active_session_id
                    , status
                    , attempt_count
                    , next_attempt_at
                    , created_at
                    , updated_at )
            select  aiChatSession.id
                  , aiChatSession.id
                  , 'PENDING'
                  , 0
                  , :now
                  , :now
                  , :now
              from  ai_chat_session aiChatSession
             where  aiChatSession.status = 'ACTIVE'
               and  aiChatSession.accumulated_tokens >= :minTokens
               and  exists (
                        select 1
                          from ai_chat_message aiChatMessage
                         where aiChatMessage.session_id = aiChatSession.id
                           and aiChatMessage.status = 'COMPLETED'
                           and aiChatMessage.created_at >= :since
                    )
               and  not exists (
                        select 1
                          from summary_job summaryJob
                         where summaryJob.active_session_id = aiChatSession.id
                    )
            """, nativeQuery = true)
    int enqueuePendingForEligibleSessions(
            @Param("minTokens") int minTokens,
            @Param("since") LocalDateTime since,
            @Param("now") LocalDateTime now);

    /**
     * 등록 도서(UserBook) 삭제 cascade 용 — 그 도서의 세션들에 속한 작업을 일괄 삭제한다.
     * summary_job 은 userBookId 를 갖지 않으므로 세션(AiChatSession) 서브쿼리로 좁힌다.
     * 세션이 먼저 삭제되면 서브쿼리가 비므로, 반드시 세션 삭제 전에 호출한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from SummaryJob summaryJob
             where summaryJob.aiChatSessionId in (
                   select aiChatSession.id
                     from AiChatSession aiChatSession
                    where aiChatSession.userBookId = :userBookId
                 )
            """)
    int deleteAllByUserBookId(@Param("userBookId") Long userBookId);
}
