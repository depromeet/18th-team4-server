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

    @Test
    void create_는_제목_생성_전_빈_제목이_노출되지_않도록_기본_제목을_박는다() {
        AiChatSession session = AiChatSession.create(1L);

        assertThat(session.hasTitle()).isTrue();
        assertThat(session.getTitle()).isEqualTo(AiChatSession.DEFAULT_TITLE);
    }

    @Test
    void updateTitle_은_생성에_성공한_제목으로_기본_제목을_덮어쓴다() {
        AiChatSession session = AiChatSession.create(1L);

        session.updateTitle("데미안을 읽고 나눈 대화");

        assertThat(session.getTitle()).isEqualTo("데미안을 읽고 나눈 대화");
    }
}
