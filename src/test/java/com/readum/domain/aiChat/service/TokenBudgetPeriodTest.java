package com.readum.domain.aiChat.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetPeriodTest {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    private static Clock fixedAtKst(int year, int month, int day, int hour, int minute) {
        return Clock.fixed(
                ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE_KST).toInstant(), ZONE_KST);
    }

    @Test
    void periodKey_는_clock_시간대의_달력_날짜를_yyyyMMdd_정수로_만든다() {
        TokenBudgetPeriod period = TokenBudgetPeriod.current(fixedAtKst(2026, 9, 4, 10, 30));

        assertThat(period.periodKey()).isEqualTo(20260904);
    }

    @Test
    void untilNextMidnight_는_다음_자정까지_남은_시간이다() {
        TokenBudgetPeriod period = TokenBudgetPeriod.current(fixedAtKst(2026, 9, 4, 10, 30));

        assertThat(period.untilNextMidnight()).isEqualTo(Duration.ofHours(13).plusMinutes(30));
    }

    @Test
    void UTC_날짜와_달라도_clock_시간대_KST_날짜_기준으로_기간을_정한다() {
        // UTC 2026-09-04 16:00 = KST 2026-09-05 01:00 — KST 기준 다음 날이다.
        Clock clock = Clock.fixed(java.time.Instant.parse("2026-09-04T16:00:00Z"), ZONE_KST);

        TokenBudgetPeriod period = TokenBudgetPeriod.current(clock);

        assertThat(period.periodKey()).isEqualTo(20260905);
        assertThat(period.untilNextMidnight()).isEqualTo(Duration.ofHours(23));
    }
}
