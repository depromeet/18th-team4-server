package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThatCode;

class SummaryCallRateLimiterImplTest {

    @Test
    @Timeout(5)
    void 충분한_예산이면_즉시_통과한다() {
        SummaryCallRateLimiterImpl limiter =
                new SummaryCallRateLimiterImpl(new SummaryRateLimitProperties(60, 100_000));

        // 시작 시 양동이가 가득 차 있으므로 첫 호출은 대기 없이 통과한다.
        assertThatCode(() -> limiter.acquire(2_000)).doesNotThrowAnyException();
    }

    @Test
    @Timeout(5)
    void 예상토큰이_TPM용량을_초과해도_클램프되어_매달리지_않는다() {
        SummaryCallRateLimiterImpl limiter =
                new SummaryCallRateLimiterImpl(new SummaryRateLimitProperties(60, 1_000));

        // 5000 > 용량 1000 → 1000 으로 클램프되어 첫 호출에 통과(무한 대기 방지).
        assertThatCode(() -> limiter.acquire(5_000)).doesNotThrowAnyException();
    }
}
