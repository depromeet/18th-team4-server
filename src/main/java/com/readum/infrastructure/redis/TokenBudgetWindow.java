package com.readum.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 사용자 토큰 예산의 고정 창 — 그 시간대(clock 의 zone)의 자정 앵커로 windowHours 칸씩 나눈다.
 * 예: 4시간이면 00·04·08·12·16·20시 시작. Redis 키의 창 식별자(startEpochSecond)와
 * 429 Retry-After(untilNext) 계산에 쓴다.
 */
public record TokenBudgetWindow(long startEpochSecond, Duration untilNext) {

    public static TokenBudgetWindow current(Clock clock, int windowHours) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        int slotStartHour = (now.getHour() / windowHours) * windowHours;
        ZonedDateTime windowStart = now.toLocalDate().atTime(slotStartHour, 0).atZone(clock.getZone());
        ZonedDateTime windowEnd = windowStart.plusHours(windowHours);
        return new TokenBudgetWindow(windowStart.toEpochSecond(), Duration.between(now, windowEnd));
    }
}
