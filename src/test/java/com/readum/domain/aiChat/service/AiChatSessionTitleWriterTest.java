package com.readum.domain.aiChat.service;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiChatSessionTitleWriterTest {

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @InjectMocks
    private AiChatSessionTitleWriter titleWriter;

    @Test
    void 세션이_존재하면_title_이_갱신된다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, null
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));

        titleWriter.updateTitle(sessionId, "작가의 의도 분석");

        assertThat(session.getTitle()).isEqualTo("작가의 의도 분석");
    }

    @Test
    void 긴_제목도_엔티티가_컬럼_길이까지_자른다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, null
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));

        titleWriter.updateTitle(sessionId, "가".repeat(150));

        assertThat(session.getTitle()).hasSize(100);
    }

    @Test
    void 갱신_시점에_세션이_이미_삭제되어_없으면_예외_없이_조용히_끝난다() {
        Long sessionId = 7L;
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.empty());

        assertThatCode(() -> titleWriter.updateTitle(sessionId, "작가의 의도 분석"))
                .doesNotThrowAnyException();
    }

    @Test
    void 제목이_없는_세션에는_기본_제목을_대체해_넣는다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, null
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));

        titleWriter.applyDefaultTitleIfAbsent(sessionId);

        assertThat(session.getTitle()).isEqualTo(AiChatSession.DEFAULT_TITLE);
    }

    @Test
    void 이미_제목이_있는_세션은_기본_제목_대체로_덮어쓰지_않는다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, "작가의 의도 분석"
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));

        titleWriter.applyDefaultTitleIfAbsent(sessionId);

        assertThat(session.getTitle()).isEqualTo("작가의 의도 분석");
    }
}
