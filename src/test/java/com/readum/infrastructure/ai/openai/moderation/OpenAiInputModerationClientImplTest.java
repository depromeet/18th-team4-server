package com.readum.infrastructure.ai.openai.moderation;

import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.infrastructure.ai.openai.GuardrailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.CategoryScores;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OpenAiInputModerationClientImplTest {

    private static final AiChatStreamCommand.BookContext BOOK_CONTEXT =
            new AiChatStreamCommand.BookContext("살인의 추억", "작가", "출판사");

    private static final List<String> ALWAYS_BLOCK =
            List.of("self-harm", "sexual-minors", "dangerous-and-criminal-content");
    private static final List<String> RELAXED =
            List.of("violence", "violence-graphic", "harassment", "sexual", "hate");

    private OpenAiInputModerationClientImpl adapter(
            ModerationModel model, GuardrailProperties.Moderation.FailurePolicy policy
    ) {
        GuardrailProperties.Moderation moderation = new GuardrailProperties.Moderation(
                true, "omni-moderation-latest", ALWAYS_BLOCK, RELAXED, policy);
        GuardrailProperties props = new GuardrailProperties(null, null, moderation, null);
        return new OpenAiInputModerationClientImpl(model, props);
    }

    private OpenAiInputModerationClientImpl adapter(ModerationModel model) {
        return adapter(model, GuardrailProperties.Moderation.FailurePolicy.CLOSED);
    }

    @Test
    void AC1_책_맥락이_있고_violence만_flagged_면_통과한다() {
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any())).willReturn(flagged(Categories.builder().violence(true).build()));

        InputModerationResult result = adapter(model).check("살인범이 누구야?", BOOK_CONTEXT);

        assertThat(result.isPassed()).isTrue();
    }

    @Test
    void AC2_책_맥락이_없으면_violence_flagged_는_차단된다() {
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any())).willReturn(flagged(Categories.builder().violence(true).build()));

        InputModerationResult result = adapter(model).check("살인범이 누구야?", null);

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.flaggedCategories()).contains("violence");
    }

    @Test
    void AC3_책_맥락이_있어도_self_harm_flagged_면_차단된다() {
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any())).willReturn(flagged(Categories.builder().selfHarm(true).build()));

        InputModerationResult result = adapter(model).check("자해 방법 알려줘", BOOK_CONTEXT);

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.flaggedCategories()).contains("self-harm");
    }

    @Test
    void violence와_self_harm이_동시_flagged_면_하나라도_always_block_이라_차단된다() {
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any()))
                .willReturn(flagged(Categories.builder().violence(true).selfHarm(true).build()));

        InputModerationResult result = adapter(model).check("...", BOOK_CONTEXT);

        assertThat(result.isBlocked()).isTrue();
    }

    @Test
    void relaxed_밖_카테고리가_flagged_면_책_맥락이_있어도_차단된다() {
        // hate-threatening 은 relaxed 목록에 없음 → 책 맥락이 있어도 차단(맥락-완화는 relaxed 한정).
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any())).willReturn(flagged(Categories.builder().hateThreatening(true).build()));

        InputModerationResult result = adapter(model).check("...", BOOK_CONTEXT);

        assertThat(result.isBlocked()).isTrue();
    }

    @Test
    void flagged_가_없으면_통과한다() {
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any())).willReturn(clean());

        InputModerationResult result = adapter(model).check("이 책의 줄거리 요약해줘", BOOK_CONTEXT);

        assertThat(result.isPassed()).isTrue();
    }

    @Test
    void 빈_입력은_API_호출_없이_통과한다() {
        ModerationModel model = mock(ModerationModel.class);

        InputModerationResult result = adapter(model).check("   ", BOOK_CONTEXT);

        assertThat(result.isPassed()).isTrue();
        org.mockito.Mockito.verify(model, org.mockito.Mockito.never()).call(any());
    }

    @Test
    void AC10_API_예외_시_failurePolicy_OPEN_이면_통과하고_WARN() {
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any())).willThrow(new RuntimeException("OpenAI Moderation 502"));

        InputModerationResult result =
                adapter(model, GuardrailProperties.Moderation.FailurePolicy.OPEN).check("질문", BOOK_CONTEXT);

        assertThat(result.isPassed()).isTrue();
    }

    @Test
    void AC9_API_예외_시_failurePolicy_CLOSED_이면_UNAVAILABLE() {
        ModerationModel model = mock(ModerationModel.class);
        given(model.call(any())).willThrow(new RuntimeException("OpenAI Moderation 502"));

        InputModerationResult result =
                adapter(model, GuardrailProperties.Moderation.FailurePolicy.CLOSED).check("질문", BOOK_CONTEXT);

        assertThat(result.isUnavailable()).isTrue();
    }

    @Test
    void AC11_두_카테고리_목록의_교집합이_공집합이_아니면_부팅_검증이_실패한다() {
        ModerationModel model = mock(ModerationModel.class);
        GuardrailProperties.Moderation moderation = new GuardrailProperties.Moderation(
                true, "omni-moderation-latest",
                List.of("self-harm", "violence"),   // violence 가 양쪽에 모두 존재
                List.of("violence", "harassment"),
                GuardrailProperties.Moderation.FailurePolicy.CLOSED);
        OpenAiInputModerationClientImpl impl =
                new OpenAiInputModerationClientImpl(model, new GuardrailProperties(null, null, moderation, null));

        assertThatThrownBy(impl::validateAndLogConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("교집합");
    }

    @Test
    void 알_수_없는_카테고리_이름이_설정되면_부팅_검증이_실패한다() {
        ModerationModel model = mock(ModerationModel.class);
        GuardrailProperties.Moderation moderation = new GuardrailProperties.Moderation(
                true, "omni-moderation-latest",
                List.of("self-harm", "illicit-violent"),   // illicit-violent 는 이 Spring AI 버전에 없음
                RELAXED,
                GuardrailProperties.Moderation.FailurePolicy.CLOSED);
        OpenAiInputModerationClientImpl impl =
                new OpenAiInputModerationClientImpl(model, new GuardrailProperties(null, null, moderation, null));

        assertThatThrownBy(impl::validateAndLogConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("illicit-violent");
    }

    @Test
    void 기본_카테고리_설정은_부팅_검증을_통과한다() {
        ModerationModel model = mock(ModerationModel.class);
        GuardrailProperties.Moderation moderation = GuardrailProperties.Moderation.defaults();
        OpenAiInputModerationClientImpl impl =
                new OpenAiInputModerationClientImpl(model, new GuardrailProperties(null, null, moderation, null));

        impl.validateAndLogConfig();   // 예외 없이 통과해야 한다
    }

    private static ModerationResponse flagged(Categories categories) {
        ModerationResult result = ModerationResult.builder()
                .flagged(true)
                .categories(categories)
                .categoryScores(CategoryScores.builder().build())
                .build();
        Moderation moderation = Moderation.builder()
                .id("modr-test")
                .model("omni-moderation-latest")
                .results(List.of(result))
                .build();
        return new ModerationResponse(new org.springframework.ai.moderation.Generation(moderation));
    }

    private static ModerationResponse clean() {
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
