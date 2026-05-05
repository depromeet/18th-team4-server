package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT aiChatSession FROM AiChatSession aiChatSession WHERE aiChatSession.id = :id")
    Optional<AiChatSession> findByIdForUpdate(@Param("id") Long id);
}
