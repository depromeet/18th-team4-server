package com.readum.domain.summary.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 감상문 작업 큐의 비즈니스 룰(환경 무관 상수). 잘못된 값(0/음수)은 부팅 시 차단(@Validated).
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
        @Positive long breakerOpenSeconds,
        @Positive int reservedOutputTokens,
        @Positive long pacedRetrySeconds
) {

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    /** quota 소진 감지 시 전역 차단을 유지할 시간. */
    public Duration breakerOpen() {
        return Duration.ofSeconds(breakerOpenSeconds);
    }

    /** 지수 백오프: base * 2^attemptCount (attemptCount = 지금까지의 시도 횟수). */
    public LocalDateTime nextAttemptFrom(LocalDateTime now, int attemptCount) {
        long seconds = baseBackoffSeconds * (1L << Math.min(attemptCount, 10));
        return now.plusSeconds(seconds);
    }
}
