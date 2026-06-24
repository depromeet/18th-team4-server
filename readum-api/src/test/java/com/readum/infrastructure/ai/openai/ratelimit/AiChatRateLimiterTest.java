package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.infrastructure.ai.openai.GuardrailProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("guardrail")
class AiChatRateLimiterTest {

    @Test
    void 분당_요청_한도를_초과하면_더_이상_허용되지_않는다() {
        GuardrailProperties props = new GuardrailProperties(
                GuardrailProperties.Input.defaults(),
                GuardrailProperties.Output.defaults(),
                GuardrailProperties.Moderation.defaults(),
                new GuardrailProperties.RateLimit(true, 3, 100)
        );
        AiChatRateLimiter limiter = new AiChatRateLimiter(props);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("user:1")).as("호출 %d", i + 1).isTrue();
        }
        assertThat(limiter.tryConsume("user:1")).as("4번째 호출은 한도 초과").isFalse();
    }

    @Test
    void 일일_한도가_분당_한도보다_먼저_소진되면_차단된다() {
        GuardrailProperties props = new GuardrailProperties(
                GuardrailProperties.Input.defaults(),
                GuardrailProperties.Output.defaults(),
                GuardrailProperties.Moderation.defaults(),
                new GuardrailProperties.RateLimit(true, 100, 2)
        );
        AiChatRateLimiter limiter = new AiChatRateLimiter(props);

        assertThat(limiter.tryConsume("user:1")).isTrue();
        assertThat(limiter.tryConsume("user:1")).isTrue();
        assertThat(limiter.tryConsume("user:1")).as("일일 한도 2 를 초과").isFalse();
    }

    @Test
    void 서로_다른_key_는_각자_독립적인_bucket_을_가진다() {
        GuardrailProperties props = new GuardrailProperties(
                GuardrailProperties.Input.defaults(),
                GuardrailProperties.Output.defaults(),
                GuardrailProperties.Moderation.defaults(),
                new GuardrailProperties.RateLimit(true, 1, 100)
        );
        AiChatRateLimiter limiter = new AiChatRateLimiter(props);

        assertThat(limiter.tryConsume("user:A")).isTrue();
        assertThat(limiter.tryConsume("user:A")).isFalse();
        assertThat(limiter.tryConsume("user:B")).isTrue();
    }

    @Test
    void enabled_가_false_면_언제나_허용한다() {
        GuardrailProperties props = new GuardrailProperties(
                GuardrailProperties.Input.defaults(),
                GuardrailProperties.Output.defaults(),
                GuardrailProperties.Moderation.defaults(),
                new GuardrailProperties.RateLimit(false, 1, 1)
        );
        AiChatRateLimiter limiter = new AiChatRateLimiter(props);

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryConsume("user:X")).isTrue();
        }
        assertThat(limiter.isEnabled()).isFalse();
    }
}
