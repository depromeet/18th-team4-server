package com.readum.infrastructure.ai.openai.circuitbreaker;

import com.readum.domain.summary.out.SummaryCallBreaker;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 메모리 기반 전역 차단 스위치. 차단 만료 시각만 들고 있어, 만료가 지나면 스스로 풀린다.
 * 영속하지 않으므로 재시작하면 풀린 상태로 시작하고, 문제가 계속되면 다음 호출이 다시 막아준다.
 */
@Component
public class InMemorySummaryCallBreaker implements SummaryCallBreaker {

    private final Clock clock;
    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.EPOCH);

    public InMemorySummaryCallBreaker() {
        this(Clock.systemUTC());
    }

    // 테스트에서 시간을 제어하기 위한 생성자 (package-private).
    InMemorySummaryCallBreaker(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isBlocked() {
        return clock.instant().isBefore(blockedUntil.get());
    }

    @Override
    public void blockFor(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        Instant candidate = clock.instant().plus(duration);
        // 동시에 들어온 짧은 차단(burst)이 이미 설정된 긴 차단(quota)을 되감지 않도록 단조 증가시킨다.
        blockedUntil.updateAndGet(current -> candidate.isAfter(current) ? candidate : current);
    }
}
