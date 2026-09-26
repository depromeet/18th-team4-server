package com.readum.domain.aiChat.listener;

import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.event.FirstAssistantResponseCompletedEvent;
import com.readum.domain.aiChat.service.AiChatSessionTitleService;
import com.readum.model.aiChat.entity.AiChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiChatTitleGenerationListenerTest {

    private final AiChatSessionTitleService aiChatSessionTitleService = mock(AiChatSessionTitleService.class);
    // 즉시 실행 executor 로 offload 를 동기화해 검증을 단순화한다(운영에선 가상 스레드 executor).
    private final Executor aiChatVirtualThreadExecutor = Runnable::run;
    private final AiChatTitleGenerationListener listener =
            new AiChatTitleGenerationListener(aiChatSessionTitleService, aiChatVirtualThreadExecutor);

    @Test
    void 이벤트를_받으면_유저_첫_질문으로_제목_생성을_실행한다() {
        AiChatMessage firstUserMessage = AiChatMessage.createUserMessage(7L, "첫 질문");

        listener.onFirstAssistantResponseCompleted(
                new FirstAssistantResponseCompletedEvent(7L, firstUserMessage));

        ArgumentCaptor<GenerateSessionTitleCommand> captor =
                ArgumentCaptor.forClass(GenerateSessionTitleCommand.class);
        verify(aiChatSessionTitleService).execute(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(7L);
        assertThat(captor.getValue().messages())
                .as("제목 생성 입력은 이벤트에 담긴 유저 첫 질문 하나여야 한다")
                .containsExactly(firstUserMessage);
    }

    @Test
    void 제목_생성이_실패해도_예외를_전파하지_않는다() {
        AiChatMessage firstUserMessage = AiChatMessage.createUserMessage(7L, "첫 질문");
        doThrow(new RuntimeException("LLM 실패")).when(aiChatSessionTitleService).execute(any());

        assertThatCode(() -> listener.onFirstAssistantResponseCompleted(
                new FirstAssistantResponseCompletedEvent(7L, firstUserMessage)))
                .doesNotThrowAnyException();
    }
}
