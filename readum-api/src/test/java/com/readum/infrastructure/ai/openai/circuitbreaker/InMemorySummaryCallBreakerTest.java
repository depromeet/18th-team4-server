package com.readum.infrastructure.ai.openai.circuitbreaker;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySummaryCallBreakerTest {

    @Test
    void 초기에는_막혀_있지_않다() {
        InMemorySummaryCallBreaker breaker = new InMemorySummaryCallBreaker(new MutableClock(Instant.now()));

        assertThat(breaker.isBlocked()).isFalse();
    }

    @Test
    void blockFor_후에는_막힌다() {
        InMemorySummaryCallBreaker breaker = new InMemorySummaryCallBreaker(new MutableClock(Instant.now()));

        breaker.blockFor(Duration.ofMinutes(5));

        assertThat(breaker.isBlocked()).isTrue();
    }

    @Test
    void 차단_시간이_지나면_스스로_풀린다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-18T00:00:00Z"));
        InMemorySummaryCallBreaker breaker = new InMemorySummaryCallBreaker(clock);

        breaker.blockFor(Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(6));

        assertThat(breaker.isBlocked()).isFalse();
    }

    /** 시간을 직접 전진시켜 차단 만료를 결정론적으로 검증하기 위한 테스트용 Clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
