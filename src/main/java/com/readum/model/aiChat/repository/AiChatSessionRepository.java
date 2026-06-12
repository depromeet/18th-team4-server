package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
import com.readum.model.summary.entity.Summary;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 특정 userBook 의 채팅 세션 목록 페이지 조회.
     *
     * 정렬 / lastChattedAt: 마지막으로 노출 가능한 메시지 (status COMPLETED) 의
     * createdAt 을 max() 서브쿼리로 구해 사용한다. 메시지가 아직 없는 세션은 session.createdAt 으로 fallback.
     * AiChatSession.updatedAt 을 쓰지 않는 이유 — lock() / unlock() / updateTitle() 같은 비-채팅 이벤트가
     * 갱신해 "최근 채팅 시각" 의 의미가 흐려지기 때문.
     *
     * status 도출: AiChatSession.status (ACTIVE/LOCKED) 와 세션의 최신 Summary.status (COMPLETED/FAILED) 를
     * CASE 로 합성해 단일 문자열로 반환한다. 매핑 규칙:
     *  - session LOCKED                          → "SUMMARIZING" (감상문 생성 중 — 직전 감상문 존재 여부와 무관)
     *  - session ACTIVE + 감상문 없음             → "ACTIVE"
     *  - session ACTIVE + 최신 감상문 FAILED      → "FAILED"
     *  - session ACTIVE + 최신 감상문 COMPLETED   → "SUMMARIZED"
     *
     * 감상문은 세션당 여러 건(재생성 이력) 존재할 수 있으므로 left join 의 on 절에서
     * max(id) 서브쿼리로 최신 한 건만 매칭한다.
     * Summary 와 AiChatSession 사이에 JPA 연관관계가 없어 left join 의 on 절로 직접 매칭한다.
     * Hibernate 6+ 의 entity-without-association join 문법.
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
                Summary.Status.FAILED,
                Summary.Status.COMPLETED,
                AiChatMessage.Status.COMPLETED,
                pageable
        );
    }

    @Query("""
            select new com.readum.model.aiChat.repository.projection.AiChatSessionListProjection(
                       aiChatSession.id
                     , aiChatSession.title
                     , case
                           when aiChatSession.status = :lockedStatus then 'SUMMARIZING'
                           when summary.status = :summaryFailedStatus then 'FAILED'
                           when summary.status = :summaryCompletedStatus then 'SUMMARIZED'
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
              left join Summary summary
                     on summary.aiChatSessionId = aiChatSession.id
                    and summary.id = (select max(otherSummary.id)
                                        from Summary otherSummary
                                       where otherSummary.aiChatSessionId = aiChatSession.id)
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
            @Param("summaryFailedStatus") Summary.Status summaryFailedStatus,
            @Param("summaryCompletedStatus") Summary.Status summaryCompletedStatus,
            @Param("messageCompletedStatus") AiChatMessage.Status messageCompletedStatus,
            Pageable pageable
    );
}
