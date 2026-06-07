package com.readum.infrastructure.ai.openai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부분 바인딩 시 sub-record 의 누락된 필드가 defaults 로 보완되는지 검증.
 * 운영 YAML 에서 readum.guardrail.input 블록은 있지만 injectionPatterns 가 누락되면
 * PromptInjectionPatternAdvisor 가 침묵 미등록되어 보안 기능이 무력화될 수 있다.
 */
@Tag("guardrail")
class GuardrailPropertiesTest {

    @Test
    void Input_의_injectionPatterns_가_빈_리스트면_defaults_로_보강된다() {
        GuardrailProperties.Input input = new GuardrailProperties.Input(
                4000,
                1500,
                List.of(),
                List.of(),                          // 누락된 것과 동등 — 반드시 defaults 로 보강되어야 함
                "거부 메시지"
        );

        assertThat(input.injectionPatterns())
                .isNotEmpty()
                .containsAll(GuardrailProperties.Input.defaults().injectionPatterns());
    }

    @Test
    void Input_의_injectionPatterns_가_null_이면_defaults_로_보강된다() {
        GuardrailProperties.Input input = new GuardrailProperties.Input(
                4000,
                1500,
                List.of(),
                null,
                "거부 메시지"
        );

        assertThat(input.injectionPatterns())
                .isNotEmpty()
                .containsAll(GuardrailProperties.Input.defaults().injectionPatterns());
    }

    @Test
    void Input_의_failureResponse_가_blank_이면_defaults_로_보강된다() {
        GuardrailProperties.Input input = new GuardrailProperties.Input(
                4000,
                1500,
                List.of(),
                List.of("(?i)foo"),
                "   "
        );

        assertThat(input.failureResponse()).isEqualTo(GuardrailProperties.Input.defaults().failureResponse());
    }

    @Test
    void Input_의_maxCharacters_가_0_이하면_defaults_로_보강된다() {
        GuardrailProperties.Input input = new GuardrailProperties.Input(
                0,
                0,
                List.of(),
                List.of("(?i)foo"),
                "거부 메시지"
        );

        assertThat(input.maxCharacters()).isEqualTo(GuardrailProperties.Input.defaults().maxCharacters());
        assertThat(input.maxTokens()).isEqualTo(GuardrailProperties.Input.defaults().maxTokens());
    }

    @Test
    void Output_의_maxResponseTokens_가_0_이하면_defaults_로_보강된다() {
        GuardrailProperties.Output output = new GuardrailProperties.Output(0, "거부");

        assertThat(output.maxResponseTokens())
                .isEqualTo(GuardrailProperties.Output.defaults().maxResponseTokens());
    }

    @Test
    void RateLimit_의_한도가_0_이하면_defaults_로_보강된다() {
        GuardrailProperties.RateLimit rateLimit = new GuardrailProperties.RateLimit(true, 0, 0);

        assertThat(rateLimit.requestsPerMinute())
                .isEqualTo(GuardrailProperties.RateLimit.defaults().requestsPerMinute());
        assertThat(rateLimit.dailyRequests())
                .isEqualTo(GuardrailProperties.RateLimit.defaults().dailyRequests());
    }

    @Test
    void Moderation_의_model_이_blank_이면_defaults_로_보강된다() {
        GuardrailProperties.Moderation moderation =
                new GuardrailProperties.Moderation("", List.of("self-harm"), List.of("violence"),
                        GuardrailProperties.Moderation.FailurePolicy.CLOSED);

        assertThat(moderation.model()).isEqualTo(GuardrailProperties.Moderation.defaults().model());
    }

    @Test
    void Moderation_의_카테고리_목록과_failurePolicy_가_null_이면_defaults_로_보강된다() {
        GuardrailProperties.Moderation moderation =
                new GuardrailProperties.Moderation("omni-moderation-latest", null, null, null);

        assertThat(moderation.alwaysBlockCategories())
                .isEqualTo(GuardrailProperties.Moderation.defaults().alwaysBlockCategories());
        assertThat(moderation.bookContextRelaxedCategories())
                .isEqualTo(GuardrailProperties.Moderation.defaults().bookContextRelaxedCategories());
        assertThat(moderation.failurePolicy())
                .isEqualTo(GuardrailProperties.Moderation.FailurePolicy.CLOSED);
    }

    @Test
    void Moderation_defaults_의_두_카테고리_목록은_교집합이_없다() {
        GuardrailProperties.Moderation defaults = GuardrailProperties.Moderation.defaults();

        assertThat(defaults.alwaysBlockCategories())
                .doesNotContainAnyElementsOf(defaults.bookContextRelaxedCategories());
    }

    @Test
    void 최상위_record_에서_sub_record_가_null_이면_각각의_defaults_가_적용된다() {
        GuardrailProperties properties = new GuardrailProperties(null, null, null, null);

        assertThat(properties.input()).isNotNull();
        assertThat(properties.output()).isNotNull();
        assertThat(properties.moderation()).isNotNull();
        assertThat(properties.rateLimit()).isNotNull();
        assertThat(properties.input().injectionPatterns()).isNotEmpty();
    }
}
