package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatTitleClient;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;

import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatSessionTitleServiceTest {

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatTitleClient aiChatTitleClient;

    @Mock
    private AiChatSessionTitleWriter aiChatSessionTitleWriter;

    @InjectMocks
    private AiChatSessionTitleService titleService;

    @Test
    void 세션이_존재하고_LLM_이_제목을_반환하면_생성된_제목으로_갱신을_위임한다() {
        Long sessionId = 7L;
        List<AiChatMessage> messages = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, sessionId, "작가의 의도가 뭐야"),
                AiChatMessageFixture.persistedAssistantMessage(2L, sessionId, "작가는 ...")
        );
        given(aiChatSessionRepository.existsById(sessionId)).willReturn(true);
        given(aiChatTitleClient.generate(messages)).willReturn("작가의 의도 분석");

        titleService.execute(new GenerateSessionTitleCommand(sessionId, messages));

        verify(aiChatSessionTitleWriter).updateTitle(sessionId, "작가의 의도 분석");
    }

    @Test
    void 세션이_없으면_NotFoundException_을_던지고_LLM_은_호출되지_않는다() {
        Long sessionId = 7L;
        given(aiChatSessionRepository.existsById(sessionId)).willReturn(false);

        assertThatThrownBy(() -> titleService.execute(new GenerateSessionTitleCommand(sessionId, List.of())))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verify(aiChatTitleClient, never()).generate(anyList());
    }

    @Test
    void LLM_이_빈_제목을_반환하면_갱신을_위임하지_않는다() {
        Long sessionId = 7L;
        List<AiChatMessage> messages = List.of();
        given(aiChatSessionRepository.existsById(sessionId)).willReturn(true);
        given(aiChatTitleClient.generate(messages)).willReturn("   ");

        titleService.execute(new GenerateSessionTitleCommand(sessionId, messages));

        verify(aiChatSessionTitleWriter, never()).updateTitle(anyLong(), anyString());
    }
}
