package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    Page<AiChatMessage> findBySessionIdOrderByCreatedAtDescIdDesc(Long sessionId, Pageable pageable);

    /**
     * 컨텍스트 윈도우용 최근 메시지 조회.
     * status=COMPLETED 인 USER/ASSISTANT 메시지만 최신순으로 가져온다 (FAILED 메시지 제외).
     */
    default List<AiChatMessage> findRecentForContextWindow(Long sessionId, Pageable pageable) {
        return findBySessionIdAndStatusAndRoleInOrderByCreatedAtDescIdDesc(
                sessionId,
                AiChatMessage.Status.COMPLETED,
                List.of(AiChatMessage.Role.USER, AiChatMessage.Role.ASSISTANT),
                pageable
        );
    }

    List<AiChatMessage> findBySessionIdAndStatusAndRoleInOrderByCreatedAtDescIdDesc(
            Long sessionId,
            AiChatMessage.Status status,
            Collection<AiChatMessage.Role> roles,
            Pageable pageable
    );
}
