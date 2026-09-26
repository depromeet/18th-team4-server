package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.summary.out.AiQuotaCooldown;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** quota 쿨다운 조회를 전역 게이트에 위임 — 게이트가 쓰는 Redis 키를 그대로 읽어 상태를 공유한다. */
@Component
@RequiredArgsConstructor
public class AiQuotaCooldownRedisAdapter implements AiQuotaCooldown {

    private final OpenAiRequestGate requestGate;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Override
    public boolean isCoolingDown() {
        return requestGate.isInQuotaCooldown(chatModel);
    }
}
