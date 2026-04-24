package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AiStreamChatServiceTest {

    @Mock
    private ChatModel chatModel;

    private AiStreamChatService aiStreamChatService;

    @BeforeEach
    void setUp() {
        // 실제 ChatClient를 mocking된 ChatModel로 구성 (RETURNS_DEEP_STUBS 없이 깔끔하게)
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        aiStreamChatService = new AiStreamChatService(chatClient);
    }

    @Test
    void 스트리밍_응답_청크가_순서대로_반환된다() {
        // given
        AiChatCommand command = new AiChatCommand("책의 줄거리를 요약해줘");

        ChatResponse chunk1 = createChatResponseWithText("이 책은");
        ChatResponse chunk2 = createChatResponseWithText(" 모험에");
        ChatResponse chunk3 = createChatResponseWithText(" 관한 이야기입니다.");

        // ChatModel.stream(Prompt)을 명시 → stream(String) 오버로딩과 구분
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(chunk1, chunk2, chunk3));

        // when
        Flux<String> result = aiStreamChatService.stream(command);

        // then
        StepVerifier.create(result)
                .expectNext("이 책은")
                .expectNext(" 모험에")
                .expectNext(" 관한 이야기입니다.")
                .verifyComplete();
    }

    @Test
    void 마지막_청크에_토큰_정보가_있으면_로깅이_처리되고_에러_없이_완료된다() {
        // given
        AiChatCommand command = new AiChatCommand("독서 감상문 작성해줘");

        ChatResponse chunk1 = createChatResponseWithText("이 소설은");
        ChatResponse chunk2 = createChatResponseWithText(" 감동적입니다.");

        // 마지막 청크에만 토큰 메타데이터 포함 (실제 OpenAI 스트림과 동일한 패턴)
        Usage usage = mock(Usage.class);
        given(usage.getTotalTokens()).willReturn(30);
        given(usage.getPromptTokens()).willReturn(20);
        given(usage.getCompletionTokens()).willReturn(10);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        given(metadata.getUsage()).willReturn(usage);

        ChatResponse lastChunkWithUsage = new ChatResponse(
                List.of(new Generation(new AssistantMessage(" 강력 추천합니다."))),
                metadata
        );

        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(chunk1, chunk2, lastChunkWithUsage));

        // when
        Flux<String> result = aiStreamChatService.stream(command);

        // then
        StepVerifier.create(result)
                .expectNext("이 소설은")
                .expectNext(" 감동적입니다.")
                .expectNext(" 강력 추천합니다.")
                .verifyComplete();
    }

    @Test
    void 스트림_도중_에러가_발생하면_에러가_전파된다() {
        // given
        AiChatCommand command = new AiChatCommand("에러 발생 케이스");
        RuntimeException expectedException = new RuntimeException("OpenAI API 연결 실패");

        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.error(expectedException));

        // when
        Flux<String> result = aiStreamChatService.stream(command);

        // then
        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().equals("OpenAI API 연결 실패"))
                .verify();
    }

    private ChatResponse createChatResponseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
