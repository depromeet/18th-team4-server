package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import jakarta.persistence.LockModeType;
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
}
