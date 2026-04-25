package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {
}
