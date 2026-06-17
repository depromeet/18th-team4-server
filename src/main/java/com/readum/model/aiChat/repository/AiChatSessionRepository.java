package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    /**
     * 세션 소유권 검증.
     * AiChatSession 에 userId 컬럼을 직접 두지 않고 UserBook 을 EXISTS 서브쿼리로 거치는 이유:
     * 데이터 정규성 유지 — UserBook 이 사용자-도서 소유 관계의 기준 데이터이므로
     * 세션은 userBookId 만 참조한다. 매 호출마다 EXISTS 비용이 들지만 MVP 트래픽에서 무시 가능.
     */
    @Query("""
            select aiChatSession
              from AiChatSession aiChatSession
             where aiChatSession.id = :sessionId
               and exists (
                     select 1
                       from UserBook userBook
                      where userBook.id = aiChatSession.userBookId
                        and userBook.userId = :userId
                   )
            """)
    Optional<AiChatSession> findByIdAndOwner(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId
    );

    /**
     * 감상문 초안 생성 시 동시 요청에 대한 중복 처리 방지를 위한 비관적 락 조회.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select aiChatSession
              from AiChatSession aiChatSession
             where aiChatSession.id = :id
            """)
    Optional<AiChatSession> findByIdForUpdate(@Param("id") Long id);

    /** 한 권(userBook)에 속한 모든 채팅 세션 — 책별 세션 목록 합성용. */
    List<AiChatSession> findByUserBookId(Long userBookId);

    /**
     * 특정 userBook 의 채팅 세션 목록 페이지 조회.
     *
     * 정렬 / lastChattedAt: 마지막으로 노출 가능한 메시지 (status COMPLETED) 의
     * createdAt 을 max() 서브쿼리로 구해 사용한다. 메시지가 아직 없는 세션은 session.createdAt 으로 fallback.
     * AiChatSession.updatedAt 을 쓰지 않는 이유 — lock() / unlock() / updateTitle() 같은 비-채팅 이벤트가
     * 갱신해 "최근 채팅 시각" 의 의미가 흐려지기 때문.
     *
     * status 도출: AiChatSession.status (ACTIVE/LOCKED) 와 진행 중 작업(summary_job) 존재 여부를 CASE 로 합성한다.
     *  - session LOCKED                                               → "SUMMARIZED" (감상문이 완성되어 종료된 세션)
     *  - 수동(SYNC) PENDING, 유효 점유(lockedUntil > now) PROCESSING/BATCH_BUILDING, 또는 SUBMITTED 작업 존재
     *                                → "SUMMARIZING" (감상문 생성 진행 중 — SummaryJobRepository.existsBlockingSummaryJob 과 동일 조건)
     *  - 그 외                        → "ACTIVE"
     * 점유 만료 PROCESSING/BATCH_BUILDING 은 "생성 중" 으로 보지 않으므로 lockedUntil > now 로 거른다.
     * SYNC PENDING 을 포함하는 이유: 수동 요청은 "요청 시점" 기준이므로 워커가 작업을 집어가기 전에도 화면상 SUMMARIZING 으로 표시해야 한다.
     *
     * 호출자 시그니처를 단순하게 유지하기 위해 default 메서드로 감싸고, 내부 @Query 에 enum 파라미터를 바인딩한다.
     */
    default Slice<AiChatSessionListProjection> findSessionsByUserBookIdAndOwner(
            Long userBookId, Long userId, Pageable pageable
    ) {
        return findSessionsByUserBookIdAndOwnerInternal(
                userBookId,
                userId,
                AiChatSession.Status.LOCKED,
                AiChatMessage.Status.COMPLETED,
                LocalDateTime.now(),
                pageable
        );
    }

    @Query("""
            select new com.readum.model.aiChat.repository.projection.AiChatSessionListProjection(
                       aiChatSession.id
                     , aiChatSession.title
                     , case
                           when aiChatSession.status = :lockedStatus then 'SUMMARIZED'
                           when exists (
                                    select 1
                                      from SummaryJob summaryJob
                                     where summaryJob.aiChatSessionId = aiChatSession.id
                                       and (
                                             (summaryJob.executionMode = com.readum.model.summary.entity.SummaryJob.ExecutionMode.SYNC
                                              and summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.PENDING)
                                          or (summaryJob.status in (
                                                    com.readum.model.summary.entity.SummaryJob.Status.PROCESSING
                                                  , com.readum.model.summary.entity.SummaryJob.Status.BATCH_BUILDING
                                              ) and summaryJob.lockedUntil > :now)
                                          or summaryJob.status = com.readum.model.summary.entity.SummaryJob.Status.SUBMITTED
                                       )
                                ) then 'SUMMARIZING'
                           else 'ACTIVE'
                       end
                     , coalesce(
                           (select max(aiChatMessage.createdAt)
                              from AiChatMessage aiChatMessage
                             where aiChatMessage.sessionId = aiChatSession.id
                               and aiChatMessage.status = :messageCompletedStatus),
                           aiChatSession.createdAt
                       )
                   )
              from AiChatSession aiChatSession
             where aiChatSession.userBookId = :userBookId
               and exists (
                     select 1
                       from UserBook userBook
                      where userBook.id = aiChatSession.userBookId
                        and userBook.userId = :userId
                   )
             order by
                coalesce(
                    (select max(aiChatMessage.createdAt)
                       from AiChatMessage aiChatMessage
                      where aiChatMessage.sessionId = aiChatSession.id
                        and aiChatMessage.status = :messageCompletedStatus),
                    aiChatSession.createdAt
                ) desc,
                aiChatSession.id desc
            """)
    Slice<AiChatSessionListProjection> findSessionsByUserBookIdAndOwnerInternal(
            @Param("userBookId") Long userBookId,
            @Param("userId") Long userId,
            @Param("lockedStatus") AiChatSession.Status lockedStatus,
            @Param("messageCompletedStatus") AiChatMessage.Status messageCompletedStatus,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    /**
     * 자동 요약 대상 세션 id 목록.
     * 대상 = ACTIVE + 누적 토큰 ≥ minTokens + 마지막 COMPLETED 메시지가 since 이후(최근 채팅 활동).
     * 24시간 판정을 session.updatedAt(비-채팅 이벤트로 오염됨) 이 아니라 실제 메시지 시각으로 한다.
     */
    @Query("""
            select aiChatSession.id
              from AiChatSession aiChatSession
             where aiChatSession.status = :activeStatus
               and aiChatSession.accumulatedTokens >= :minTokens
               and exists (
                     select 1
                       from AiChatMessage aiChatMessage
                      where aiChatMessage.sessionId = aiChatSession.id
                        and aiChatMessage.status = :messageCompletedStatus
                        and aiChatMessage.createdAt >= :since
                   )
            """)
    List<Long> findAutoSummaryTargetSessionIds(
            @Param("activeStatus") AiChatSession.Status activeStatus,
            @Param("minTokens") int minTokens,
            @Param("messageCompletedStatus") AiChatMessage.Status messageCompletedStatus,
            @Param("since") LocalDateTime since);

    /**
     * 등록 도서(UserBook) 삭제 cascade 용 — 그 도서의 모든 채팅 세션을 일괄 삭제한다.
     * 세션에 속한 메시지(AiChatMessage) 를 먼저 삭제한 뒤 호출해야 메시지 고아가 남지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AiChatSession aiChatSession where aiChatSession.userBookId = :userBookId")
    int deleteAllByUserBookId(@Param("userBookId") Long userBookId);
}
