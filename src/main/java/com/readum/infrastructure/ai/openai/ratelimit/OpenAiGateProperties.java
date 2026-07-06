package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * OpenAI 전역 게이트의 모델별 분당 예산(계정 실한도의 90% — 운영값).
 * 모든 outbound 경로(채팅·제목·감상문·요약 갱신)가 호출 전 이 예산에서 계상한다.
 * 외부 API 설정이므로 infrastructure 에 둔다. moderation 은 별도 모델·별도 한도라 대상 아님.
 */
@Validated
@ConfigurationProperties(prefix = "openai-gate")
public record OpenAiGateProperties(
        @NotEmpty Map<String, @Valid ModelLimit> models
) {

    public record ModelLimit(
            @Positive int requestsPerMinute,
            @Positive long tokensPerMinute
    ) {
    }
}
