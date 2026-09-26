package com.readum.domain.aiChat.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 완성된 답변 보관소의 손잡이.
 *
 * @param retentionHours 끝까지 만들어진 결과의 보관 시간. <b>DB 가 한동안 응답하지 않아도 살아 있어야</b>
 *                       하므로 길게 둔다. 이 시간이 지나도록 DB 에 확정하지 못한 결과는 사라진다 —
 *                       그것이 이 보관소가 구할 수 있는 시간의 상한이다.
 */
@Validated
@ConfigurationProperties(prefix = "ai-chat.completed-turn")
public record CompletedTurnStoreProperties(@Positive long retentionHours) {

    public Duration retention() {
        return Duration.ofHours(retentionHours);
    }
}
