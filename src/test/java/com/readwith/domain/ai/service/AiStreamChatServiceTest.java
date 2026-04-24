package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.out.AiChatClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiStreamChatServiceTest {

    @Mock
    private AiChatClient aiChatClient;

    @InjectMocks
    private AiStreamChatService aiStreamChatService;

    @Test
    void 스트리밍_응답_청크가_message_이벤트로_순서대로_반환된다() {
        AiChatCommand command = new AiChatCommand("책의 줄거리를 요약해줘");
        given(aiChatClient.stream(command))
                .willReturn(Flux.just("이 책은", " 모험에", " 관한 이야기입니다."));

        Flux<ServerSentEvent<String>> result = aiStreamChatService.stream(command);

        StepVerifier.create(result)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("message");
                    assertThat(sse.data()).isEqualTo("이 책은");
                })
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("message");
                    assertThat(sse.data()).isEqualTo(" 모험에");
                })
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("message");
                    assertThat(sse.data()).isEqualTo(" 관한 이야기입니다.");
                })
                .verifyComplete();
    }

    @Test
    void 스트림_도중_에러가_발생하면_error_이벤트를_방출하고_정상_완료된다() {
        AiChatCommand command = new AiChatCommand("에러 발생 케이스");
        given(aiChatClient.stream(command))
                .willReturn(Flux.error(new RuntimeException("OpenAI API 연결 실패")));

        Flux<ServerSentEvent<String>> result = aiStreamChatService.stream(command);

        StepVerifier.create(result)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("error");
                    assertThat(sse.data()).isEqualTo("통신 중 문제가 발생했습니다.");
                })
                .verifyComplete();
    }
}
