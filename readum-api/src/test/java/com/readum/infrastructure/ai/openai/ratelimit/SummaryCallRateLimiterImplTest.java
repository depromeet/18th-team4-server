package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryCallRateLimiterImplTest {

    @Test
    @Timeout(5)
    void 충분한_예산이면_true를_반환한다() throws InterruptedException {
        SummaryCallRateLimiterImpl limiter = new SummaryCallRateLimiterImpl(
                new SummaryRateLimitProperties(60, 100_000, 130_000, 2));

        // 시작 시 양동이가 가득 차 있으므로 첫 호출은 대기 없이 통과한다.
        assertThat(limiter.tryAcquire(2_000)).isTrue();
    }

    @Test
    @Timeout(5)
    void 단일_요청이_분당_TPM보다_커도_용량_안이면_통과한다() throws InterruptedException {
        // 분당 TPM 40000 < 단일 요청 50000 이지만, 용량(maxRequestTokens)=130000 이라 통과한다.
        SummaryCallRateLimiterImpl limiter = new SummaryCallRateLimiterImpl(
                new SummaryRateLimitProperties(60, 40_000, 130_000, 2));

        assertThat(limiter.tryAcquire(50_000)).isTrue();
    }

    @Test
    @Timeout(10)
    void 토큰_예산이_소진되면_대기시간_내_미확보로_false를_반환한다() throws InterruptedException {
        // 용량 1000 / 분당 1000(greedy 라 1초에 ~16개만 회복). 첫 호출이 전부 소진,
        // 둘째는 1초 안에 1000 회복 불가 → false.
        SummaryCallRateLimiterImpl limiter = new SummaryCallRateLimiterImpl(
                new SummaryRateLimitProperties(60, 1_000, 1_000, 1));

        assertThat(limiter.tryAcquire(1_000)).isTrue();
        assertThat(limiter.tryAcquire(1_000)).isFalse();
    }

    @Test
    void maxRequestTokens_는_설정값을_그대로_노출한다() {
        SummaryCallRateLimiterImpl limiter = new SummaryCallRateLimiterImpl(
                new SummaryRateLimitProperties(60, 100_000, 120_000, 2));

        assertThat(limiter.maxRequestTokens()).isEqualTo(120_000);
    }
}
