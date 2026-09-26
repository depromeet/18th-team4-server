package com.readum.domain.summary.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 감상문 작업 큐의 비즈니스 룰(환경 무관 상수). 잘못된 값(0/음수)은 부팅 시 차단(@Validated).
 * maxRequestTokens: 단일 요청 추정 토큰 상한 — 초과 세션은 워커가 호출 없이 즉시 실패 처리 (구 페이서 버킷 용량에서 이사).
 */
@Validated
@ConfigurationProperties(prefix = "summary-job")
public record SummaryJobProperties(
        @Positive int poolSize,
        @Positive long dispatchIntervalMs,
        @Positive long reaperIntervalMs,
        @Positive long leaseSeconds,
        @Positive int maxAttempts,
        @Positive long baseBackoffSeconds,
        @Positive int maxRequestTokens,
        @Positive int estimatedOutputTokens
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
