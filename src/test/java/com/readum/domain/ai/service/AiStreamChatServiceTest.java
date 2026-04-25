package com.readum.domain.ai.service;

import com.readum.domain.ai.dto.AiChatCommand;
import com.readum.domain.ai.out.AiChatClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    void 스트리밍_응답_청크가_순서대로_반환된다() {
        AiChatCommand command = new AiChatCommand("책의 줄거리를 요약해줘");
        given(aiChatClient.stream(command))
                .willReturn(Flux.just("이 책은", " 모험에", " 관한 이야기입니다."));

        Flux<String> result = aiStreamChatService.stream(command);

        StepVerifier.create(result)
                .assertNext(text -> assertThat(text).isEqualTo("이 책은"))
                .assertNext(text -> assertThat(text).isEqualTo(" 모험에"))
                .assertNext(text -> assertThat(text).isEqualTo(" 관한 이야기입니다."))
                .verifyComplete();
    }

    @Test
    void 스트림_도중_에러가_발생하면_에러가_전파된다() {
        AiChatCommand command = new AiChatCommand("에러 발생 케이스");
        RuntimeException expectedException = new RuntimeException("OpenAI API 연결 실패");
        given(aiChatClient.stream(command))
                .willReturn(Flux.error(expectedException));

        Flux<String> result = aiStreamChatService.stream(command);

        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().equals("OpenAI API 연결 실패"))
                .verify();
    }
}
