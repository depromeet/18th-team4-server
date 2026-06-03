package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.out.InputModerationClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * OpenAiConfig.inputModerationClient 빈 팩토리의 네 가지 분기 검증(AC-12, AC-13).
 * 무거운 Spring 컨텍스트 대신 팩토리 메서드를 직접 호출한다.
 */
class OpenAiConfigInputModerationClientTest {

    private final OpenAiConfig config = new OpenAiConfig();

    private GuardrailProperties propsWithModeration(boolean enabled) {
        GuardrailProperties.Moderation moderation = new GuardrailProperties.Moderation(
                enabled, "omni-moderation-latest", null, null, null);
        return new GuardrailProperties(null, null, moderation, null);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ModerationModel> provider(ModerationModel model) {
        ObjectProvider<ModerationModel> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(model);
        return provider;
    }

    @Test
    void AC13_moderation_enabled_false_면_no_op_빈이_등록되어_모든_입력을_통과시킨다() {
        InputModerationClient client = config.inputModerationClient(
                propsWithModeration(false), provider(null));

        InputModerationResult result = client.check("무엇이든", null);

        assertThat(result.isPassed()).isTrue();
    }

    @Test
    void enabled_true_이고_ModerationModel_빈이_있으면_OpenAi_구현체가_등록된다() {
        InputModerationClient client = config.inputModerationClient(
                propsWithModeration(true), provider(mock(ModerationModel.class)));

        assertThat(client).isInstanceOf(
                com.readum.infrastructure.ai.openai.moderation.OpenAiInputModerationClientImpl.class);
    }

    @Test
    void AC12_enabled_true_인데_ModerationModel_빈이_없으면_부팅이_실패한다() {
        assertThatThrownBy(() -> config.inputModerationClient(
                propsWithModeration(true), provider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ModerationModel");
    }
}
