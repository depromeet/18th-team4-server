package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.summary.out.SummaryCallRateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * bucket4j 기반 outbound 페이서. 요청 수(RPM)와 토큰 수(TPM)를 각각 양동이로 관리하고,
 * 호출 직전 둘 다 확보될 때까지 블로킹 대기시킨다. 단일 인스턴스 in-memory.
 */
@Slf4j
@Component
public class SummaryCallRateLimiterImpl implements SummaryCallRateLimiter {

    private final Bucket requestBucket;
    private final Bucket tokenBucket;
    private final long tokenCapacity;

    public SummaryCallRateLimiterImpl(SummaryRateLimitProperties properties) {
        this.requestBucket = perMinuteBucket(properties.requestsPerMinute());
        this.tokenCapacity = properties.tokensPerMinute();
        this.tokenBucket = perMinuteBucket(tokenCapacity);
    }

    @Override
    public void acquire(int estimatedTokens) throws InterruptedException {
        long tokens = Math.max(1, Math.min(estimatedTokens, tokenCapacity));
        if (estimatedTokens > tokenCapacity) {
            // 한 요청이 분당 토큰 용량보다 크면 영원히 대기하게 되므로 용량으로 클램프한다.
            log.warn("요약 예상 토큰({})이 TPM 용량({})을 초과해 용량으로 클램프합니다.", estimatedTokens, tokenCapacity);
        }
        requestBucket.asBlocking().consume(1);
        tokenBucket.asBlocking().consume(tokens);
    }

    private static Bucket perMinuteBucket(long perMinute) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(perMinute)
                .refillIntervally(perMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder()
                .addLimit(bandwidth)
                .build();
    }
}
