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
import java.util.Optional;

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
     * 컨텍스트 조립용 최근 메시지 조회.
     * status=COMPLETED 인 메시지만 최신순으로 가져온다 (FAILED 메시지 제외).
     * 실질 상한은 호출자의 토큰 예산이며, Pageable 은 안전 상한 개수로만 쓴다.
     */
    default List<AiChatMessage> findRecentForContextAssembly(Long sessionId, Pageable pageable) {
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
     * 세션의 첫 정상(COMPLETED) USER 메시지. 제목 생성이 "유저의 첫 질문" 기반으로 동작하도록 사용한다.
     * REJECTED(가드레일 차단) USER 메시지는 제외되므로, 차단된 입력이 먼저 있었더라도 첫 정상 질문이 잡힌다.
     */
    default Optional<AiChatMessage> findFirstUserMessage(Long sessionId) {
        return findFirstBySessionIdAndRoleAndStatusOrderByCreatedAtAscIdAsc(
                sessionId, AiChatMessage.Role.USER, AiChatMessage.Status.COMPLETED);
    }

    Optional<AiChatMessage> findFirstBySessionIdAndRoleAndStatusOrderByCreatedAtAscIdAsc(
            Long sessionId, AiChatMessage.Role role, AiChatMessage.Status status);

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
     * 요약 반영 지점 이후의 유효(COMPLETED) 원문 메시지를 id 오름차순으로 — 컨텍스트 요약 워커의 델타 원문·범위 계산용.
     * summarizedUpToMessageId(요약이 커버한 마지막 메시지 id, 없으면 0) 초과분만 반환한다. id 는 IDENTITY 라 시간순과 단조 일치.
     */
    default List<AiChatMessage> findCompletedMessagesAfter(Long sessionId, Long summarizedUpToMessageId) {
        return findBySessionIdAndStatusAndIdGreaterThanOrderByIdAsc(
                sessionId, AiChatMessage.Status.COMPLETED, summarizedUpToMessageId);
    }

    List<AiChatMessage> findBySessionIdAndStatusAndIdGreaterThanOrderByIdAsc(
            Long sessionId, AiChatMessage.Status status, Long id);

    /**
     * 요약 반영 지점 이후 최근 원문 대화의 token_count 합 — 요약 트리거 판정용(임계값 초과 시 job 적재).
     * token_count 가 null 인 행은 SUM 에서 무시된다. 행이 없으면 coalesce 로 0.
     *
     * 암묵적 계약: 이 합이 정확하려면 COMPLETED 메시지의 token_count 가 반드시 채워져 있어야 한다
     * (선택기·조립기와 달리 여기엔 content 길이 fallback 이 없다). AiChatMessagePersistService 가 저장 시
     * USER=jtokkit 로컬 계산, ASSISTANT=실측 출력(없으면 로컬 계산)으로 항상 채워 이 계약을 지킨다.
     */
    @Query("""
            select coalesce(sum(aiChatMessage.tokenCount), 0)
              from AiChatMessage aiChatMessage
             where aiChatMessage.sessionId = :sessionId
               and aiChatMessage.status = com.readum.model.aiChat.entity.AiChatMessage.Status.COMPLETED
               and aiChatMessage.id > :summarizedUpToMessageId
            """)
    long sumRecentMessageTokens(@Param("sessionId") Long sessionId, @Param("summarizedUpToMessageId") Long summarizedUpToMessageId);

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
