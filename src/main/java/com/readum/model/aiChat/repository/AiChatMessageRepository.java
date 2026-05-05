package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    @Query("""
            SELECT aiChatMessage
              FROM AiChatMessage aiChatMessage
             WHERE aiChatMessage.sessionId = :sessionId
               AND aiChatMessage.status <> com.readum.model.aiChat.entity.AiChatMessage.Status.FAILED
             ORDER BY aiChatMessage.createdAt ASC
            """)
    List<AiChatMessage> findValidMessagesBySessionIdOrderByCreatedAtAsc(@Param("sessionId") Long sessionId);
}
