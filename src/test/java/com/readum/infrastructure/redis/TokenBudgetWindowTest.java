package com.readum.infrastructure.redis;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetWindowTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Clock fixedAt(String kstDateTime) {
        Instant instant = ZonedDateTime.of(
                java.time.LocalDateTime.parse(kstDateTime), KST).toInstant();
        return Clock.fixed(instant, KST);
    }

    @Test
    void 창은_KST_자정_앵커_4시간_칸이다() {
        TokenBudgetWindow window = TokenBudgetWindow.current(fixedAt("2026-07-06T05:30:00"), 4);
        // 05:30 KST → 창 시작 04:00 KST
        assertThat(window.startEpochSecond())
                .isEqualTo(ZonedDateTime.of(java.time.LocalDateTime.parse("2026-07-06T04:00:00"), KST).toEpochSecond());
    }

    @Test
    void 다음_창까지_남은_시간을_계산한다() {
        TokenBudgetWindow window = TokenBudgetWindow.current(fixedAt("2026-07-06T05:30:00"), 4);
        assertThat(window.untilNext()).isEqualTo(Duration.ofHours(2).plusMinutes(30));
    }

    @Test
    void 자정_직전_창은_20시_시작이고_다음_창은_자정이다() {
        TokenBudgetWindow window = TokenBudgetWindow.current(fixedAt("2026-07-06T23:59:00"), 4);
        assertThat(window.startEpochSecond())
                .isEqualTo(ZonedDateTime.of(java.time.LocalDateTime.parse("2026-07-06T20:00:00"), KST).toEpochSecond());
        assertThat(window.untilNext()).isEqualTo(Duration.ofMinutes(1));
    }
}
