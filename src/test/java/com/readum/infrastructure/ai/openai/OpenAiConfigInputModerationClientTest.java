package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.out.InputModerationClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * OpenAiConfig.inputModerationClient 빈 팩토리 검증.
 * moderation 은 끌 수 없으므로 ModerationModel 빈 존재 여부에 따라 구현체 등록 / 부팅 실패만 갈린다.
 * 무거운 Spring 컨텍스트 대신 팩토리 메서드를 직접 호출한다.
 */
class OpenAiConfigInputModerationClientTest {

    private final OpenAiConfig config = new OpenAiConfig();

    private GuardrailProperties props() {
        GuardrailProperties.Moderation moderation = new GuardrailProperties.Moderation(
                "omni-moderation-latest", null, null, null);
        return new GuardrailProperties(null, null, moderation);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ModerationModel> provider(ModerationModel model) {
        ObjectProvider<ModerationModel> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(model);
        return provider;
    }

    @Test
    void ModerationModel_빈이_있으면_OpenAi_구현체가_등록된다() {
        InputModerationClient client = config.inputModerationClient(
                props(), provider(mock(ModerationModel.class)));

        assertThat(client).isInstanceOf(
                com.readum.infrastructure.ai.openai.moderation.OpenAiInputModerationClientImpl.class);
    }

    @Test
    void ModerationModel_빈이_없으면_부팅이_실패한다() {
        assertThatThrownBy(() -> config.inputModerationClient(props(), provider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ModerationModel");
    }
}
