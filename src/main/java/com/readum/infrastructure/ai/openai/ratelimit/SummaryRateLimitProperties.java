package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 감상문 배치가 OpenAI 에 쓸 수 있는 호출 예산(운영값).
 * 값 산정: OpenAI 계정 천장(모델·등급별 RPM/TPM) × 배치 몫(라이브 채팅과 공유하므로 일부) × 안전여유.
 * 외부 API 설정이므로 infrastructure 에 둔다.
 */
@Validated
@ConfigurationProperties(prefix = "summary.rate-limit")
public record SummaryRateLimitProperties(
        @Positive int requestsPerMinute,
        @Positive long tokensPerMinute
) {
}
