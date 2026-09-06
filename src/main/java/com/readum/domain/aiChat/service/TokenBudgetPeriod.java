package com.readum.domain.aiChat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 사용자 토큰 예산의 일 단위 기간 — clock 시간대(KST)의 달력 날짜 하나가 한 기간이다.
 * periodKey 는 날짜의 정수 표기(yyyyMMdd, 예: 20260904)로 원장(user_token_budget)의 기간 식별자,
 * untilNextMidnight 는 429 Retry-After(다음 KST 자정까지 남은 시간) 계산에 쓴다.
 */
record TokenBudgetPeriod(int periodKey, Duration untilNextMidnight) {

    static TokenBudgetPeriod current(Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        int periodKey = today.getYear() * 10_000 + today.getMonthValue() * 100 + today.getDayOfMonth();
        ZonedDateTime nextMidnight = today.plusDays(1).atStartOfDay(clock.getZone());
        return new TokenBudgetPeriod(periodKey, Duration.between(now, nextMidnight));
    }
}
