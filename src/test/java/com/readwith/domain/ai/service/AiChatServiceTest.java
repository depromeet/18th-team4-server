package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.dto.AiChatResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

    @Mock
    private ChatModel chatModel;

    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        // 실제 ChatClient를 mocking된 ChatModel로 구성 (RETURNS_DEEP_STUBS 없이 깔끔하게)
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        aiChatService = new AiChatService(chatClient);
    }

    @Test
    void 정상_메시지를_전달하면_AI_응답과_토큰_로깅이_처리된다() {
        // given
        AiChatCommand command = new AiChatCommand("오늘 읽은 책에 대해 알려줘");
        String expectedAnswer = "오늘 읽은 책은 정말 흥미롭군요!";

        Usage usage = mock(Usage.class);
        given(usage.getTotalTokens()).willReturn(15);
        given(usage.getPromptTokens()).willReturn(10);
        given(usage.getCompletionTokens()).willReturn(5);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        given(metadata.getUsage()).willReturn(usage);

        Generation generation = new Generation(new AssistantMessage(expectedAnswer));
        ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);

        // ChatModel.call(Prompt)을 명시 → call(String)→String 오버로딩과 구분
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponse);

        // when
        AiChatResult result = aiChatService.execute(command);

        // then
        assertThat(result.answer()).isEqualTo(expectedAnswer);
    }

    @Test
    void 토큰_사용량이_0이면_토큰_로깅을_건너뛰고_응답을_반환한다() {
        // given
        AiChatCommand command = new AiChatCommand("책 추천해줘");
        String expectedAnswer = "좋은 책을 추천해드릴게요.";

        Usage usage = mock(Usage.class);
        // getTotalTokens() > 0 필터가 false → getPromptTokens/getCompletionTokens는 호출되지 않음
        given(usage.getTotalTokens()).willReturn(0);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        given(metadata.getUsage()).willReturn(usage);

        Generation generation = new Generation(new AssistantMessage(expectedAnswer));
        ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);

        given(chatModel.call(any(Prompt.class))).willReturn(chatResponse);

        // when
        AiChatResult result = aiChatService.execute(command);

        // then
        assertThat(result.answer()).isEqualTo(expectedAnswer);
    }
}
