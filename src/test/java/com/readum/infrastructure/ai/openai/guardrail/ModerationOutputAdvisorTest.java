package com.readum.infrastructure.ai.openai.guardrail;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@Tag("guardrail")
class ModerationOutputAdvisorTest {

    private static final String FAILURE_MSG = "응답을 안전하게 생성할 수 없어 거부되었습니다.";

    @Test
    void 비스트리밍_응답이_flagged_면_거부_메시지로_교체된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(flaggedResponse());
        ModerationOutputAdvisor advisor = new ModerationOutputAdvisor(moderationModel, FAILURE_MSG, 1000);

        ChatClientRequest request = newRequest("질문");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(responseFromAssistant("위험한 답변"));

        ChatClientResponse response = advisor.adviseCall(request, chain);

        assertThat(extractText(response)).isEqualTo(FAILURE_MSG);
    }

    @Test
    void 비스트리밍_응답이_clean_이면_원본_응답_그대로_반환된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(cleanResponse());
        ModerationOutputAdvisor advisor = new ModerationOutputAdvisor(moderationModel, FAILURE_MSG, 1000);

        ChatClientRequest request = newRequest("질문");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(responseFromAssistant("안전한 답변"));

        ChatClientResponse response = advisor.adviseCall(request, chain);

        assertThat(extractText(response)).isEqualTo("안전한 답변");
    }

    @Test
    void 스트리밍_누적_결과가_flagged_면_단일_거부_청크로_교체된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(flaggedResponse());
        ModerationOutputAdvisor advisor = new ModerationOutputAdvisor(moderationModel, FAILURE_MSG, 1000);

        ChatClientRequest request = newRequest("질문");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        given(chain.nextStream(any())).willReturn(Flux.just(
                responseFromAssistant("이 책은"),
                responseFromAssistant(" 매우 "),
                responseFromAssistant("위험합니다.")
        ));

        Flux<ChatClientResponse> flux = advisor.adviseStream(request, chain);

        StepVerifier.create(flux)
                .assertNext(r -> assertThat(extractText(r)).isEqualTo(FAILURE_MSG))
                .verifyComplete();
    }

    @Test
    void 스트리밍_차단_시_마지막_chunk_의_context_가_보존된다() {
        // 상위 advisor 들이 스트림 처리 과정에서 누적시킨 context 메타데이터를 잃지 않아야 한다.
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(flaggedResponse());
        ModerationOutputAdvisor advisor = new ModerationOutputAdvisor(moderationModel, FAILURE_MSG, 1000);

        ChatClientRequest request = newRequest("질문");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatClientResponse last = ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("위험"))))
                        .build())
                .context(Map.of("upstream-meta", "from-last-chunk"))
                .build();
        given(chain.nextStream(any())).willReturn(Flux.just(
                responseFromAssistant("이 책은"),
                last
        ));

        Flux<ChatClientResponse> flux = advisor.adviseStream(request, chain);

        StepVerifier.create(flux)
                .assertNext(r -> {
                    assertThat(extractText(r)).isEqualTo(FAILURE_MSG);
                    assertThat(r.context()).containsEntry("upstream-meta", "from-last-chunk");
                })
                .verifyComplete();
    }

    @Test
    void 스트리밍_누적_결과가_clean_이면_원본_청크들이_순서대로_emit_된다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(cleanResponse());
        ModerationOutputAdvisor advisor = new ModerationOutputAdvisor(moderationModel, FAILURE_MSG, 1000);

        ChatClientRequest request = newRequest("질문");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        given(chain.nextStream(any())).willReturn(Flux.just(
                responseFromAssistant("청크1"),
                responseFromAssistant("청크2"),
                responseFromAssistant("청크3")
        ));

        Flux<ChatClientResponse> flux = advisor.adviseStream(request, chain);

        StepVerifier.create(flux)
                .assertNext(r -> assertThat(extractText(r)).isEqualTo("청크1"))
                .assertNext(r -> assertThat(extractText(r)).isEqualTo("청크2"))
                .assertNext(r -> assertThat(extractText(r)).isEqualTo("청크3"))
                .verifyComplete();
    }

    private static ChatClientRequest newRequest(String userMessage) {
        return new ChatClientRequest(new Prompt(List.of(new UserMessage(userMessage))), new HashMap<>());
    }

    private static ChatClientResponse responseFromAssistant(String text) {
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
        Categories cats = Categories.builder().violence(true).build();
        CategoryScores scores = CategoryScores.builder().violence(0.92).build();
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
