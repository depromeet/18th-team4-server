package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.summary.out.SummaryCallRateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * bucket4j 기반 outbound 페이서. 요청 수(RPM)와 토큰 수(TPM)를 각각 양동이로 관리한다.
 *
 * 토큰 양동이의 용량은 "분당 TPM" 이 아니라 "단일 요청 최대 토큰(maxRequestTokens)" 으로 둔다 —
 * 용량을 분당치로 두면 양동이가 가득 찰 때 1분치를 한꺼번에 쏟아내는 burst 여지가 생긴다.
 * refill 은 refillGreedy 로 1분에 걸쳐 연속 충전해, 분 경계 burst 없이 매끄럽게 페이싱한다.
 *
 * tryAcquire 는 블로킹이지만 maxWait 로 상한을 둔다 — 그 안에 예산을 못 얻으면 false 를 반환해,
 * 워커가 lease 를 오래 잡은 채 대기하다 reaper 에 중복 선점되는 것을 막는다.
 */
@Slf4j
@Component
public class SummaryCallRateLimiterImpl implements SummaryCallRateLimiter {

    private final Bucket requestBucket;
    private final Bucket tokenBucket;
    private final long maxWaitNanos;
    private final int maxRequestTokens;

    public SummaryCallRateLimiterImpl(SummaryRateLimitProperties properties) {
        this.requestBucket = bucket(properties.requestsPerMinute(), properties.requestsPerMinute());
        this.tokenBucket = bucket(properties.maxRequestTokens(), properties.tokensPerMinute());
        this.maxWaitNanos = Duration.ofSeconds(properties.acquireMaxWaitSeconds()).toNanos();
        this.maxRequestTokens = properties.maxRequestTokens();
    }

    @Override
    public boolean tryAcquire(int estimatedTokens) throws InterruptedException {
        long tokens = Math.max(1, estimatedTokens);
        if (!requestBucket.asBlocking().tryConsume(1, maxWaitNanos)) {
            return false;
        }
        if (!tokenBucket.asBlocking().tryConsume(tokens, maxWaitNanos)) {
            requestBucket.addTokens(1); // 토큰 예산 부족 — 앞서 차감한 요청 토큰을 환불
            return false;
        }
        return true;
    }

    @Override
    public int maxRequestTokens() {
        return maxRequestTokens;
    }

    private static Bucket bucket(long capacity, long refillPerMinute) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder()
                .addLimit(bandwidth)
                .build();
    }
}
