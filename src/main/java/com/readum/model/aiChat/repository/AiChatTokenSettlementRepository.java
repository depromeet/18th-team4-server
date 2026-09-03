package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatTokenSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatTokenSettlementRepository extends JpaRepository<AiChatTokenSettlement, Long> {

    boolean existsByMessageId(Long messageId);
}
