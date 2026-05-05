package com.readum.infrastructure.ai.openai.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.readum.infrastructure.ai.openai.GuardrailProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 사용자별 (또는 IP 별) AI 채팅 호출 rate limit 관리.
 *
 * 분당 요청 수 제한과 일일 요청 수 제한을 동시에 적용한다.
 * 단일 인스턴스 운영을 가정하며, in-memory(Caffeine) 캐시로 buckets 를 관리한다.
 * 향후 분산 환경 전환 시 bucket4j-redis 로 교체 예정.
 */
@Component
public class AiChatRateLimiter {

    private final GuardrailProperties properties;
    private final Cache<String, Bucket> buckets;

    public AiChatRateLimiter(GuardrailProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(2))
                .maximumSize(10_000)
                .build();
    }

    public boolean isEnabled() {
        return properties.rateLimit().enabled();
    }

    public boolean tryConsume(String key) {
        if (!isEnabled()) {
            return true;
        }
        Bucket bucket = buckets.get(key, k -> newBucket());
        return bucket.tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth perMinute = Bandwidth.builder()
                .capacity(properties.rateLimit().requestsPerMinute())
                .refillIntervally(properties.rateLimit().requestsPerMinute(), Duration.ofMinutes(1))
                .build();
        Bandwidth perDay = Bandwidth.builder()
                .capacity(properties.rateLimit().dailyRequests())
                .refillIntervally(properties.rateLimit().dailyRequests(), Duration.ofDays(1))
                .build();
        return Bucket.builder()
                .addLimit(perMinute)
                .addLimit(perDay)
                .build();
    }
}
