package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

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
     * 감상문 초안 생성용. FAILED 메시지는 제외하고 createdAt 오름차순으로 모든 유효 메시지를 조회한다.
     */
    @Query("""
            select aiChatMessage
              from AiChatMessage aiChatMessage
             where aiChatMessage.sessionId = :sessionId
               and aiChatMessage.status <> com.readum.model.aiChat.entity.AiChatMessage.Status.FAILED
             order by aiChatMessage.createdAt asc
            """)
    List<AiChatMessage> findValidMessagesBySessionIdOrderByCreatedAtAsc(@Param("sessionId") Long sessionId);

    /**
     * 사용자별 burst rate-limit 검사를 위한 카운트.
     * since 이후 생성된 USER role 메시지 수를, 소유자(userId) 가 자신의 UserBook 으로 만든 모든 세션에서 합산한다.
     * AiChatSessionRepository.findByIdAndOwner 와 동일한 패턴으로 EXISTS 서브쿼리를 거쳐
     * AiChatMessage → AiChatSession → UserBook → user_id 매핑을 수행한다.
     * role 은 FQCN 노이즈를 피하기 위해 파라미터로 바인딩하고, default 메서드가 USER 로 고정해 노출한다.
     */
    @Query("""
            select count(aiChatMessage)
              from AiChatMessage aiChatMessage
             where aiChatMessage.role = :role
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
    long countRecentMessagesByRoleAndOwner(
            @Param("role") AiChatMessage.Role role,
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since
    );

    default long countRecentUserMessagesByOwner(Long userId, LocalDateTime since) {
        return countRecentMessagesByRoleAndOwner(AiChatMessage.Role.USER, userId, since);
    }
}
