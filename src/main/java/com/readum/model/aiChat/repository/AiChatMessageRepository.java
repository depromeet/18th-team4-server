package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.projection.SessionLastChattedProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    /**
     * 여러 세션의 마지막 COMPLETED 메시지 시각을 한 번에 집계 — 책별 세션 목록의 "마지막 대화일" 합성용.
     */
    @Query("""
            select new com.readum.model.aiChat.repository.projection.SessionLastChattedProjection(
                       aiChatMessage.sessionId
                     , max(aiChatMessage.createdAt)
                   )
              from AiChatMessage aiChatMessage
             where aiChatMessage.sessionId in :sessionIds
               and aiChatMessage.status = :status
             group by aiChatMessage.sessionId
            """)
    List<SessionLastChattedProjection> findLastChattedAtBySessionIds(
            @Param("sessionIds") Collection<Long> sessionIds,
            @Param("status") AiChatMessage.Status status);

    /**
     * 사용자에게 노출할 메시지 이력 조회.
     * status=COMPLETED 인 메시지만 최신순(createdAt DESC, id DESC) 페이지네이션.
     * 스트림 중단된 FAILED 부분 응답은 제외된다.
     */
    default Slice<AiChatMessage> findVisibleHistory(Long sessionId, Pageable pageable) {
        return findSliceBySessionIdAndStatusOrderByCreatedAtDescIdDesc(
                sessionId,
                AiChatMessage.Status.COMPLETED,
                pageable
        );
    }

    Slice<AiChatMessage> findSliceBySessionIdAndStatusOrderByCreatedAtDescIdDesc(
            Long sessionId,
            AiChatMessage.Status status,
            Pageable pageable
    );

    /**
     * 컨텍스트 윈도우용 최근 메시지 조회.
     * status=COMPLETED 인 메시지만 최신순으로 가져온다 (FAILED 메시지 제외).
     */
    default List<AiChatMessage> findRecentForContextWindow(Long sessionId, Pageable pageable) {
        return findBySessionIdAndStatusOrderByCreatedAtDescIdDesc(
                sessionId,
                AiChatMessage.Status.COMPLETED,
                pageable
        );
    }

    List<AiChatMessage> findBySessionIdAndStatusOrderByCreatedAtDescIdDesc(
            Long sessionId,
            AiChatMessage.Status status,
            Pageable pageable
    );

    /**
     * LLM 프롬프트(감상문 초안 + 첫 USER 메시지 turn 의 세션 제목 생성)용 유효 메시지 조회.
     * COMPLETED 만 createdAt 오름차순으로 보낸다 — REJECTED(가드레일 차단) 와 FAILED(부분 응답) 는 제외한다.
     * 화이트리스트(status = COMPLETED) 방식: 새 상태가 추가돼도 명시적으로 허용하지 않는 한 프롬프트로 새어나가지 않는다.
     */
    @Query("""
            select aiChatMessage
              from AiChatMessage aiChatMessage
             where aiChatMessage.sessionId = :sessionId
               and aiChatMessage.status = com.readum.model.aiChat.entity.AiChatMessage.Status.COMPLETED
             order by aiChatMessage.createdAt asc
            """)
    List<AiChatMessage> findValidMessagesBySessionIdOrderByCreatedAtAsc(@Param("sessionId") Long sessionId);

    /**
     * 마지막 요약 이후 유효 메시지 조회.
     * 스케줄러가 요약 대상 메시지를 추출할 때 사용한다.
     * since 시점 이후(초과) COMPLETED 메시지만 createdAt 오름차순으로 반환한다.
     */
    default List<AiChatMessage> findValidMessagesSince(Long sessionId, LocalDateTime since) {
        return findBySessionIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                sessionId, AiChatMessage.Status.COMPLETED, since);
    }

    List<AiChatMessage> findBySessionIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
            Long sessionId, AiChatMessage.Status status, LocalDateTime createdAt);

    /**
     * 사용자별 burst rate-limit 검사를 위한 카운트.
     * since 이후 생성된, 특정 role + status 메시지 수를 소유자(userId) 가 자신의 UserBook 으로 만든 모든 세션에서 합산한다.
     * AiChatSessionRepository.findByIdAndOwner 와 동일한 패턴으로 EXISTS 서브쿼리를 거쳐
     * AiChatMessage → AiChatSession → UserBook → user_id 매핑을 수행한다.
     * role/status 는 FQCN 노이즈를 피하기 위해 파라미터로 바인딩하고, default 메서드가 의미를 고정해 노출한다.
     * 정상(COMPLETED) 과 거부(REJECTED) 카운터를 분리해, moderation false-positive 가 폭증해도
     * 정상 채팅 한도가 막히지 않게 한다.
     */
    @Query("""
            select count(aiChatMessage)
              from AiChatMessage aiChatMessage
             where aiChatMessage.role = :role
               and aiChatMessage.status = :status
               and aiChatMessage.createdAt >= :since
               and exists (
                     select 1
                       from AiChatSession aiChatSession
                          , UserBook userBook
                      where aiChatSession.id = aiChatMessage.sessionId
                        and userBook.id = aiChatSession.userBookId
                        and userBook.userId = :userId
                   )
            """)
    long countRecentMessagesByRoleStatusAndOwner(
            @Param("role") AiChatMessage.Role role,
            @Param("status") AiChatMessage.Status status,
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since
    );

    /** 최근 정상(COMPLETED) USER 메시지 수 — 정상 메시지 rate-limit 용. */
    default long countRecentUserMessagesByOwner(Long userId, LocalDateTime since) {
        return countRecentMessagesByRoleStatusAndOwner(
                AiChatMessage.Role.USER, AiChatMessage.Status.COMPLETED, userId, since);
    }

    /** 최근 거부(REJECTED) USER 메시지 수 — 어뷰즈(의도적 거부 입력 반복) rate-limit 용. */
    default long countRecentRejectedMessagesByOwner(Long userId, LocalDateTime since) {
        return countRecentMessagesByRoleStatusAndOwner(
                AiChatMessage.Role.USER, AiChatMessage.Status.REJECTED, userId, since);
    }

    /**
     * 등록 도서(UserBook) 삭제 cascade 용 — 그 도서의 모든 세션에 속한 메시지를 일괄 삭제한다.
     * AiChatMessage 는 userBookId 를 직접 갖지 않으므로 session_id 를 통해 세션을 거치는 서브쿼리로 좁힌다.
     * 삭제 대상 테이블(ai_chat_message) 과 서브쿼리 테이블(ai_chat_session) 이 달라 MySQL 8.4 의
     * "삭제 대상 테이블 자기참조 서브쿼리 금지" 제약에 걸리지 않는다.
     * 반드시 세션 삭제보다 먼저 호출해야 한다 (세션이 사라지면 이 서브쿼리가 메시지를 찾지 못한다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from AiChatMessage aiChatMessage
             where aiChatMessage.sessionId in (
                   select aiChatSession.id
                     from AiChatSession aiChatSession
                    where aiChatSession.userBookId = :userBookId
                 )
            """)
    int deleteAllByUserBookId(@Param("userBookId") Long userBookId);
}
