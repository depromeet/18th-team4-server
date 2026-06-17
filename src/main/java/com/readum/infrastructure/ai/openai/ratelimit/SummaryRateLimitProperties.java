package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 감상문 생성이 OpenAI 에 쓸 수 있는 호출 예산(운영값).
 * 값 산정: OpenAI 계정 천장(모델·등급별 RPM/TPM) × 라이브 채팅과 공유 몫 × 안전여유.
 * 외부 API 설정이므로 infrastructure 에 둔다.
 */
@Validated
@ConfigurationProperties(prefix = "summary.rate-limit")
public record SummaryRateLimitProperties(
        @Positive int requestsPerMinute,
        @Positive long tokensPerMinute,
        // 토큰 양동이 용량 = 단일 요청 토큰을 넉넉히 덮는 값. 이보다 큰 요청은 워커가 fail-fast 한다.
        @Positive int maxRequestTokens,
        // 이 시간 안에 예산을 못 얻으면 tryAcquire 가 false 를 반환한다. lease 보다 충분히 짧아야 한다.
        @Positive long acquireMaxWaitSeconds
) {
}
