package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatContextSummary;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AiChatContextSummaryRepository extends JpaRepository<AiChatContextSummary, Long> {

    /** 조립기(읽기 경로)용 — 세션의 현재 누적 요약. */
    Optional<AiChatContextSummary> findBySessionId(Long sessionId);

    /**
     * 워커의 요약 갱신(쓰기 경로)용 — 행 단위 직렬화를 위한 비관적 락 조회.
     * 갱신은 여기서 읽은 version 과 워커가 호출 전에 본 version 이 일치할 때만 반영한다(낙관적 정합성).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select aiChatContextSummary
              from AiChatContextSummary aiChatContextSummary
             where aiChatContextSummary.sessionId = :sessionId
            """)
    Optional<AiChatContextSummary> findBySessionIdForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 등록 도서(UserBook) 삭제 cascade 용 — 그 도서의 세션들에 속한 요약을 일괄 삭제한다.
     * 세션(AiChatSession) 서브쿼리로 좁힌다. 세션이 먼저 삭제되면 서브쿼리가 비므로, 반드시 세션 삭제 전에 호출한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from AiChatContextSummary aiChatContextSummary
             where aiChatContextSummary.sessionId in (
                   select aiChatSession.id
                     from AiChatSession aiChatSession
                    where aiChatSession.userBookId = :userBookId
                 )
            """)
    int deleteAllByUserBookId(@Param("userBookId") Long userBookId);
}
