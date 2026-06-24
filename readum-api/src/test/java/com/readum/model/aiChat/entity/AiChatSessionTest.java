package com.readum.model.aiChat.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatSessionTest {

    @Test
    void lock_은_세션을_영구_잠금_LOCKED로_만든다() {
        AiChatSession session = AiChatSession.create(1L);

        session.lock();

        assertThat(session.isLocked()).isTrue();
        assertThat(session.getStatus()).isEqualTo(AiChatSession.Status.LOCKED);
    }
}
