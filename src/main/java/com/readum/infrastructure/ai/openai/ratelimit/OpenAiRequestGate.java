package com.readum.infrastructure.ai.openai.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 전역 게이트 — 모델별 분당 예산(Redis 고정 창 카운터)에서 "요청 1 + 추정 토큰"을 계상한다.
 * 정책 없는 단일 컴포넌트: 초과 시 무엇을 할지(429·생략·재큐)는 각 호출 지점이 정한다.
 * 계상 대상은 실제로 쓰이는 전부다 — 시스템 프롬프트·재전송 이력·요약 등 오버헤드 포함 (사용자 예산과 다른 점).
 * 사후 보정은 하지 않는다 — 분 창은 금방 지나가고, 목적이 정밀 회계가 아니라 계정 429 예방이라서다.
 * 계정 quota 소진(계정 전역 문제)은 분당 예산으론 못 막으므로, 감지 지점이 {@link #enterQuotaCooldown} 으로
 * 쿨다운을 열면 그 동안 모든 경로를 거절한다. Redis 장애·미설정 모델은 허용 + ERROR (fail-open, 조용한 통과 방지용 로그 필수).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiRequestGate {

    private static final String KEY_PREFIX = "ai:global:";
    private static final Duration KEY_TTL = Duration.ofMinutes(2);
    private static final String QUOTA_COOLDOWN_SUFFIX = ":quota-cooldown";

    private final StringRedisTemplate stringRedisTemplate;
    private final OpenAiGateProperties properties;

    public enum RejectReason { RATE_BUDGET, QUOTA_COOLDOWN }

    public sealed interface Decision {
        record Permitted() implements Decision {
        }

        record Rejected(Duration retryAfter, RejectReason reason) implements Decision {
        }
    }

    public Decision tryAcquire(String model, int estimatedTokens) {
        OpenAiGateProperties.ModelLimit limit = properties.models().get(model);
        if (limit == null) {
            log.error("전역 게이트에 모델 한도 미설정 — 검사 없이 허용 model={}", model);
            return new Decision.Permitted();
        }
        try {
            Duration cooldown = remainingQuotaCooldown(model);
            if (cooldown != null) {
                // 계정 quota 소진 쿨다운 중 — 분당 카운터를 세지 않고 즉시 거절(감지 지점이 이미 열어 둠).
                return new Decision.Rejected(cooldown, RejectReason.QUOTA_COOLDOWN);
            }
            long epochMinute = Instant.now().getEpochSecond() / 60;
            String requestKey = KEY_PREFIX + model + ":rpm:" + epochMinute;
            String tokenKey = KEY_PREFIX + model + ":tpm:" + epochMinute;
            Long requestCount = incrementWithTtl(requestKey, 1L);
            Long tokenCount = incrementWithTtl(tokenKey, estimatedTokens);
            if (requestCount == null || tokenCount == null) {
                log.error("전역 게이트 INCRBY 응답 없음 — 검사 없이 허용 model={}", model);
                return new Decision.Permitted();
            }
            if (requestCount > limit.requestsPerMinute() || tokenCount > limit.tokensPerMinute()) {
                stringRedisTemplate.opsForValue().increment(requestKey, -1L);
                stringRedisTemplate.opsForValue().increment(tokenKey, (long) -estimatedTokens);
                return new Decision.Rejected(untilNextMinute(), RejectReason.RATE_BUDGET);
            }
            return new Decision.Permitted();
        } catch (DataAccessException e) {
            log.error("전역 게이트 Redis 접근 실패 — 검사 없이 허용 model={}", model, e);
            return new Decision.Permitted();
        }
    }

    /** 계정 quota 소진을 감지한 지점이 호출 — 이 시간 동안 전 경로를 막는다. */
    public void enterQuotaCooldown(String model, Duration cooldown) {
        try {
            stringRedisTemplate.opsForValue().set(quotaCooldownKey(model), "1", cooldown);
            log.error("전역 게이트 quota 쿨다운 진입 model={} cooldown={}s", model, cooldown.toSeconds());
        } catch (DataAccessException e) {
            log.error("전역 게이트 quota 쿨다운 기록 실패 model={}", model, e);
        }
    }

    /** 워커 사전 차단용 — 쿨다운 중이면 job 을 선점하지 않게 한다. Redis 장애 시 false(fail-open). */
    public boolean isInQuotaCooldown(String model) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(quotaCooldownKey(model)));
        } catch (DataAccessException e) {
            log.error("전역 게이트 quota 쿨다운 조회 실패 — 통과 처리 model={}", model, e);
            return false;
        }
    }

    private Duration remainingQuotaCooldown(String model) {
        Long ttlSeconds = stringRedisTemplate.getExpire(quotaCooldownKey(model), TimeUnit.SECONDS);
        return (ttlSeconds != null && ttlSeconds > 0) ? Duration.ofSeconds(ttlSeconds) : null;
    }

    private String quotaCooldownKey(String model) {
        return KEY_PREFIX + model + QUOTA_COOLDOWN_SUFFIX;
    }

    private Long incrementWithTtl(String key, long delta) {
        Long value = stringRedisTemplate.opsForValue().increment(key, delta);
        if (value != null && value == delta) {
            // 이 분(minute) 창의 첫 기록 — 창이 지나면 자동 만료
            stringRedisTemplate.expire(key, KEY_TTL);
        }
        return value;
    }

    private Duration untilNextMinute() {
        return Duration.ofSeconds(60 - (Instant.now().getEpochSecond() % 60));
    }
}
