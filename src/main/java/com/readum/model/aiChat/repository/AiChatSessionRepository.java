package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
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
     * 특정 userBook 의 채팅 세션 목록을 최근 활동 (updatedAt) 내림차순으로 페이지 조회.
     * lastChattedAt 은 entity 의 updatedAt 으로 근사 — appendUserMessage / addAssistantTokens /
     * markSummarizing / close 모두에서 갱신되므로 마지막 채팅 활동 시각과 초 단위 격차 이내로 일치한다.
     * userBook 소유권은 EXISTS 서브쿼리로 검증해 다른 사용자의 세션이 노출되지 않도록 한다.
     */
    @Query("""
            select new com.readum.model.aiChat.repository.projection.AiChatSessionListProjection(
                       aiChatSession.id
                     , aiChatSession.title
                     , aiChatSession.status
                     , aiChatSession.updatedAt
                   )
              from AiChatSession aiChatSession
             where aiChatSession.userBookId = :userBookId
               and exists (
                     select 1
                       from UserBook userBook
                      where userBook.id = aiChatSession.userBookId
                        and userBook.userId = :userId
                   )
             order by aiChatSession.updatedAt desc, aiChatSession.id desc
            """)
    Slice<AiChatSessionListProjection> findSessionsByUserBookIdAndOwner(
            @Param("userBookId") Long userBookId,
            @Param("userId") Long userId,
            Pageable pageable
    );
}
