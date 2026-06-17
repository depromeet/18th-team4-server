package com.readum.infrastructure.ai.openai.circuitbreaker;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAiCallCircuitBreakerTest {

    @Test
    void 초기에는_닫혀_있다() {
        InMemoryAiCallCircuitBreaker breaker = new InMemoryAiCallCircuitBreaker(new MutableClock(Instant.now()));

        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void openFor_후에는_열린다() {
        InMemoryAiCallCircuitBreaker breaker = new InMemoryAiCallCircuitBreaker(new MutableClock(Instant.now()));

        breaker.openFor(Duration.ofMinutes(10));

        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void 차단_시간이_지나면_스스로_닫힌다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-17T00:00:00Z"));
        InMemoryAiCallCircuitBreaker breaker = new InMemoryAiCallCircuitBreaker(clock);

        breaker.openFor(Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(11));

        assertThat(breaker.isOpen()).isFalse();
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
