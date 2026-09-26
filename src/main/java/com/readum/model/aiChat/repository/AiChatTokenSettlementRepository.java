package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatTokenSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatTokenSettlementRepository extends JpaRepository<AiChatTokenSettlement, Long> {

    /** 현재 운영 경로에서는 미사용 — 통합 테스트가 커밋 경계 밖에서 정산 기록 존재를 단언하는 용도. */
    boolean existsByMessageId(Long messageId);
}
