package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.CategoryScores;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 확인 호출 셋이 "무엇을 성공으로 보는가" 를 고정한다. 여기가 느슨하면 죽은 공급자를 살아난 것으로 판정해
 * 차단이 풀리고, 그 뒤 실제 작업들이 같은 자리에서 줄줄이 실패한다.
 *
 * <p>실제 OpenAI 를 부르지 않는다 — 모델 경계를 모의로 끼워 확인의 <b>판정 규칙</b>만 본다.
 */
class RecoveryProbeTest {

    // --- 단발 호출 확인 --------------------------------------------------------------------------

    @Test
    void 생성_결과가_실려_오면_확인된_것으로_본다() {
        ChatModel chatModel = mock(ChatModel.class);
        given(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(chatResponse("ok"));

        AiProviderRecoveryProbe.ProbeOutcome outcome = new ChatCallRecoveryProbe(
                AiAvailability.Capability.SUMMARY, chatModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Verified.class);
    }

    @Test
    void 응답_껍데기만_오면_확인_실패로_본다() {
        // 200 만으로 살아났다고 판정하면, 그 뒤 실제 작업들이 같은 빈 응답에 줄줄이 실패한다.
        ChatModel chatModel = mock(ChatModel.class);
        given(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(new ChatResponse(List.of()));

        AiProviderRecoveryProbe.ProbeOutcome outcome = new ChatCallRecoveryProbe(
                AiAvailability.Capability.SUMMARY, chatModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Failed.class);
    }

    @Test
    void 호출이_예외로_끝나면_원인을_실어_실패로_돌려준다() {
        ChatModel chatModel = mock(ChatModel.class);
        TransientAiException providerDown = new TransientAiException("OpenAI 503");
        given(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).willThrow(providerDown);

        AiProviderRecoveryProbe.ProbeOutcome outcome = new ChatCallRecoveryProbe(
                AiAvailability.Capability.CONTEXT_SUMMARY, chatModel).probe();

        assertThat(outcome)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        AiProviderRecoveryProbe.ProbeOutcome.Failed.class))
                .extracting(AiProviderRecoveryProbe.ProbeOutcome.Failed::cause)
                .isSameAs(providerDown);
    }

    // --- 채팅 스트리밍 확인 ----------------------------------------------------------------------

    @Test
    void 채팅_확인은_종료_사유를_관측했을_때만_확인된_것으로_본다() {
        // 사용량 집계를 켜 두었으므로 <b>마지막</b> 조각은 종료 사유 없이 사용량만 실려 올 수 있다.
        // 마지막 조각만 보면 정상 생성을 실패로 오판한다.
        ChatModel streamingModel = mock(ChatModel.class);
        given(streamingModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(Flux.just(
                        chatResponse("조각"),
                        finishedChunk("length"),
                        chatResponse("")));

        AiProviderRecoveryProbe.ProbeOutcome outcome = streamProbe(streamingModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Verified.class);
    }

    @Test
    void 채팅_확인은_조각이_하나도_없는_스트림을_성공으로_보지_않는다() {
        // 오류 없이 곧바로 닫히는 스트림도 있다. 그것을 성공으로 읽으면 아직 생성을 못 하는 공급자의
        // 차단을 풀어 주고, 그 뒤 실제 대화들이 같은 자리에서 줄줄이 실패한다.
        ChatModel streamingModel = mock(ChatModel.class);
        given(streamingModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(Flux.empty());

        AiProviderRecoveryProbe.ProbeOutcome outcome = streamProbe(streamingModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Failed.class);
    }

    @Test
    void 채팅_확인은_종료_사유_없이_닫힌_스트림을_성공으로_보지_않는다() {
        // 조각은 왔지만 모델이 생성을 끝냈다는 신호가 없다 — 중간에 끊긴 스트림과 구분되지 않는다.
        ChatModel streamingModel = mock(ChatModel.class);
        given(streamingModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(Flux.just(chatResponse("조각"), chatResponse("조각2")));

        AiProviderRecoveryProbe.ProbeOutcome outcome = streamProbe(streamingModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Failed.class);
    }

    @Test
    void 채팅_확인은_출력_상한에_걸려_끝난_생성도_성공으로_본다() {
        // 확인의 출력 상한을 몇 토큰으로 묶어 두었으므로 정상 확인의 종료 사유는 대개 length 다.
        // 그것을 실패로 보면 멀쩡한 공급자의 차단이 영영 풀리지 않는다.
        ChatModel streamingModel = mock(ChatModel.class);
        given(streamingModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(Flux.just(finishedChunk("length")));

        AiProviderRecoveryProbe.ProbeOutcome outcome = streamProbe(streamingModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Verified.class);
    }

    @Test
    void 채팅_확인은_스트림이_열린_뒤_끊기면_실패로_본다() {
        // 채팅이 사용자에게 실패로 보이는 방식이 바로 이것이다 — "스트림은 열렸는데 끝까지 오지 않는다".
        // 단발 호출로 갈음하면 이 구간을 지나지 않아, 열자마자 같은 곳에서 다시 막힌다.
        ChatModel streamingModel = mock(ChatModel.class);
        given(streamingModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(Flux.concat(
                        Flux.just(chatResponse("조각")),
                        Flux.error(new TransientAiException("연결이 끊겼다"))));

        AiProviderRecoveryProbe.ProbeOutcome outcome = streamProbe(streamingModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Failed.class);
    }

    @Test
    void 채팅_확인은_기한_안에_끝나지_않으면_실패로_본다() {
        ChatModel streamingModel = mock(ChatModel.class);
        given(streamingModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .willReturn(Flux.never());

        AiProviderRecoveryProbe.ProbeOutcome outcome = new ChatStreamRecoveryProbe(
                streamingModel, Duration.ofMillis(200)).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Failed.class);
    }

    // --- 검토 확인 -------------------------------------------------------------------------------

    @Test
    void 검토_확인은_판정이_실려_와야_확인된_것으로_본다() {
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(moderationResponse(true));

        AiProviderRecoveryProbe.ProbeOutcome outcome =
                new ModerationRecoveryProbe(moderationModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Verified.class);
    }

    @Test
    void 검토_확인은_판정이_비어_있으면_실패로_본다() {
        // 판정이 없는 응답을 성공으로 보면, 검토를 통과했는지 알 수 없는 입력이 "문제 없음" 으로 읽혀 모델에 나간다.
        ModerationModel moderationModel = mock(ModerationModel.class);
        given(moderationModel.call(any())).willReturn(moderationResponse(false));

        AiProviderRecoveryProbe.ProbeOutcome outcome =
                new ModerationRecoveryProbe(moderationModel).probe();

        assertThat(outcome).isInstanceOf(AiProviderRecoveryProbe.ProbeOutcome.Failed.class);
    }

    // --- 도우미 ----------------------------------------------------------------------------------

    private ChatStreamRecoveryProbe streamProbe(ChatModel streamingModel) {
        return new ChatStreamRecoveryProbe(
                streamingModel, Duration.ofSeconds(5));
    }

    /** 종료 사유가 없는 본문 조각. */
    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 모델이 생성을 끝냈다는 신호가 실린 조각. */
    private static ChatResponse finishedChunk(String finishReason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(""),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                        .finishReason(finishReason)
                        .build())));
    }

    private static ModerationResponse moderationResponse(boolean withVerdict) {
        List<ModerationResult> results = withVerdict
                ? List.of(ModerationResult.builder()
                        .flagged(false)
                        .categories(Categories.builder().build())
                        .categoryScores(CategoryScores.builder().build())
                        .build())
                : List.of();
        Moderation moderation = Moderation.builder()
                .id("modr-probe")
                .model("omni-moderation-latest")
                .results(results)
                .build();
        return new ModerationResponse(new org.springframework.ai.moderation.Generation(moderation));
    }
}
