package com.readum.domain.aiChat.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 잘못된 yml 값(0 / 음수) 으로 인한 런타임 오류를 부팅 시점에 차단하기 위해 @Validated.
// nested record 에는 @Valid 로 전파해야 안쪽 필드의 @Positive 가 검증된다.
@Validated
@ConfigurationProperties(prefix = "ai-chat")
public record AiChatProperties(
        @Valid ContextWindow contextWindow,
        @Valid MessageRule message,
        @Valid RateLimit rateLimit
) {

    /**
     * 1턴 = USER 메시지 1 + ASSISTANT 메시지 1.
     */
    public record ContextWindow(@Positive int maxTurns) {

        public int maxMessages() {
            return maxTurns * 2;
        }
    }

    public record MessageRule(@Positive int maxContentLength) {
    }

    /**
     * 사용자별 burst 호출 차단을 위한 한도. 정밀 정책은 추후 도입.
     * 현재는 OpenAI 비용 폭주(클라이언트 무한 retry, 키 유출) 방어 용도.
     * burstWindowSeconds 안에 USER 메시지가 burstMaxCount 회 이상이면 429.
     */
    public record RateLimit(
            @Positive int burstWindowSeconds,
            @Positive int burstMaxCount
    ) {
    }
}
