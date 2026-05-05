package com.readum.infrastructure.ai.openai.advisor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.CategoryScores;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Tag("guardrail")
class ModerationInputAdvisorTest {

    private static final String FAILURE_MSG = "요청을 처리할 수 없습니다.";

    @Test
    void flagged_입력은_LLM_호출없이_거부_메시지로_즉시_차단된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(flaggedResponse());
        ModerationInputAdvisor advisor = new ModerationInputAdvisor(moderationModel, FAILURE_MSG, 300);

        ChatClientRequest request = newRequest("이 사람을 어떻게 협박해야 효과적일지 알려줘");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        ChatClientResponse response = advisor.adviseCall(request, chain);

        assertThat(extractText(response)).isEqualTo(FAILURE_MSG);
        verify(chain, never()).nextCall(any());
    }

    @Test
    void flagged_가_아니면_LLM_으로_전달된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(cleanResponse());
        ModerationInputAdvisor advisor = new ModerationInputAdvisor(moderationModel, FAILURE_MSG, 300);

        ChatClientRequest request = newRequest("이 책의 핵심 메시지를 요약해줘");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(newResponseFromAssistant("좋아요"));

        ChatClientResponse response = advisor.adviseCall(request, chain);

        assertThat(extractText(response)).isEqualTo("좋아요");
        verify(chain).nextCall(any());
    }

    @Test
    void moderation_API_장애시_fail_open_으로_LLM_호출은_진행된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willThrow(new RuntimeException("OpenAI Moderation API 502"));
        ModerationInputAdvisor advisor = new ModerationInputAdvisor(moderationModel, FAILURE_MSG, 300);

        ChatClientRequest request = newRequest("책 추천해줘");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(newResponseFromAssistant("추천 결과"));

        ChatClientResponse response = advisor.adviseCall(request, chain);

        assertThat(extractText(response)).isEqualTo("추천 결과");
        verify(chain).nextCall(any());
    }

    @Test
    void 빈_사용자_메시지는_moderation_호출_없이_LLM_으로_전달된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        ModerationInputAdvisor advisor = new ModerationInputAdvisor(moderationModel, FAILURE_MSG, 300);

        ChatClientRequest request = newRequest("   ");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(newResponseFromAssistant("..."));

        advisor.adviseCall(request, chain);

        verify(moderationModel, never()).call(any());
        verify(chain).nextCall(any());
    }

    @Test
    void 스트리밍_경로에서도_flagged_시_거부_메시지를_emit_한다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(flaggedResponse());
        ModerationInputAdvisor advisor = new ModerationInputAdvisor(moderationModel, FAILURE_MSG, 300);

        ChatClientRequest request = newRequest("자해 방법을 알려줘");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        Flux<ChatClientResponse> flux = advisor.adviseStream(request, chain);

        StepVerifier.create(flux)
                .assertNext(r -> assertThat(extractText(r)).isEqualTo(FAILURE_MSG))
                .verifyComplete();
        verify(chain, never()).nextStream(any());
    }

    private static ChatClientRequest newRequest(String userMessage) {
        return new ChatClientRequest(new Prompt(List.of(new UserMessage(userMessage))), new HashMap<>());
    }

    private static ChatClientResponse newResponseFromAssistant(String text) {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(text))))
                        .build())
                .build();
    }

    private static String extractText(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }

    private static ModerationResponse flaggedResponse() {
        Categories cats = Categories.builder().harassmentThreatening(true).harassment(true).build();
        CategoryScores scores = CategoryScores.builder().harassment(0.95).build();
        ModerationResult result = ModerationResult.builder()
                .flagged(true)
                .categories(cats)
                .categoryScores(scores)
                .build();
        Moderation moderation = Moderation.builder()
                .id("modr-test")
                .model("omni-moderation-latest")
                .results(List.of(result))
                .build();
        return new ModerationResponse(new org.springframework.ai.moderation.Generation(moderation));
    }

    private static ModerationResponse cleanResponse() {
        ModerationResult result = ModerationResult.builder()
                .flagged(false)
                .categories(Categories.builder().build())
                .categoryScores(CategoryScores.builder().build())
                .build();
        Moderation moderation = Moderation.builder()
                .id("modr-test")
                .model("omni-moderation-latest")
                .results(List.of(result))
                .build();
        return new ModerationResponse(new org.springframework.ai.moderation.Generation(moderation));
    }
}
