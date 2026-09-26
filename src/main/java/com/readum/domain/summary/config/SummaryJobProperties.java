package com.readum.domain.summary.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 감상문 작업 큐의 비즈니스 룰(환경 무관 상수). 잘못된 값(0/음수)은 부팅 시 차단(@Validated).
 * maxRequestTokens: 단일 요청 추정 토큰 상한 — 초과 세션은 워커가 호출 없이 즉시 실패 처리 (구 페이서 버킷 용량에서 이사).
 * maxTotalWaitHours: 접수부터 끝나기까지 기다려 줄 전체 한도 — 넘기면 회수기가 실패로 끝낸다.
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
        @Positive int estimatedOutputTokens,
        @Positive long maxTotalWaitHours
) {

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    /**
     * 한 작업이 접수부터 끝나기까지 기다려 줄 전체 한도. 이 시간을 넘긴 미완료 작업은 회수기가 실패로 끝낸다.
     *
     * <p>왜 필요한가: 공급자가 오래 막혀 있으면 작업은 시도 횟수를 쓰지 않고 계속 대기열로 되돌아온다
     * — 재시도 상한이 작업을 끝내 주지 못한다는 뜻이다. 그런데 그 세션은 활성 작업이 있는 동안 채팅이 잠긴다.
     * 그래서 "언젠가는 끝난다" 를 보장하는 기한을 따로 둔다.
     */
    public Duration maxTotalWait() {
        return Duration.ofHours(maxTotalWaitHours);
    }

    /** 지수 백오프: base * 2^attemptCount (attemptCount = 지금까지의 시도 횟수). */
    public LocalDateTime nextAttemptFrom(LocalDateTime now, int attemptCount) {
        long seconds = baseBackoffSeconds * (1L << Math.min(attemptCount, 10));
        return now.plusSeconds(seconds);
    }
}
