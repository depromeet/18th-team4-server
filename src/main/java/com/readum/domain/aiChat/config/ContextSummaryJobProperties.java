package com.readum.domain.aiChat.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 채팅 컨텍스트 요약 작업 큐의 비즈니스 룰(환경 무관 상수). 감상문 {@code summary-job} 골격을 복제하되 별도 손잡이를 둔다.
 * maxRequestTokens: 단일 요청 추정 토큰 상한 — 초과 세션은 워커가 호출 없이 즉시 실패 처리(fail-fast).
 */
@Validated
@ConfigurationProperties(prefix = "context-summary-job")
public record ContextSummaryJobProperties(
        @Positive int poolSize,
        @Positive long dispatchIntervalMs,
        @Positive long reaperIntervalMs,
        @Positive long leaseSeconds,
        @Positive int maxAttempts,
        @Positive long baseBackoffSeconds,
        @Positive int maxRequestTokens
) {

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    /** 지수 백오프: base * 2^attemptCount (attemptCount = 지금까지의 시도 횟수). */
    public LocalDateTime nextAttemptFrom(LocalDateTime now, int attemptCount) {
        long seconds = baseBackoffSeconds * (1L << Math.min(attemptCount, 10));
        return now.plusSeconds(seconds);
    }
}
