package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatTokenSettlement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AiChatTokenSettlementRepositoryTest {

    @Autowired
    private AiChatTokenSettlementRepository aiChatTokenSettlementRepository;

    @Test
    void 같은_message_id_의_정산_기록은_UNIQUE_위반으로_두_번_저장되지_않는다() {
        long messageId = 910_001L;
        aiChatTokenSettlementRepository.saveAndFlush(AiChatTokenSettlement.create(1L, messageId, 300));

        assertThatThrownBy(() ->
                aiChatTokenSettlementRepository.saveAndFlush(AiChatTokenSettlement.create(1L, messageId, 280)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 정산_기록은_message_id_로_존재_여부를_조회할_수_있다() {
        long messageId = 910_002L;
        aiChatTokenSettlementRepository.saveAndFlush(AiChatTokenSettlement.create(1L, messageId, 300));

        assertThat(aiChatTokenSettlementRepository.existsByMessageId(messageId)).isTrue();
        assertThat(aiChatTokenSettlementRepository.existsByMessageId(910_999L)).isFalse();
    }
}
