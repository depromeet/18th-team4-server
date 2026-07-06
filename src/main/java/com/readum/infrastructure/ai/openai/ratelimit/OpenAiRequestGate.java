package com.readum.infrastructure.ai.openai.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * OpenAI 전역 게이트 — 모델별 분당 예산(Redis 고정 창 카운터)에서 "요청 1 + 추정 토큰"을 계상한다.
 * 정책 없는 단일 컴포넌트: 초과 시 무엇을 할지(429·생략·재큐)는 각 호출 지점이 정한다.
 * 계상 대상은 실제로 쓰이는 전부다 — 시스템 프롬프트·재전송 이력·요약 등 오버헤드 포함 (사용자 예산과 다른 점).
 * 사후 보정은 하지 않는다 — 분 창은 금방 지나가고, 목적이 정밀 회계가 아니라 계정 429 예방이라서다.
 * Redis 장애·미설정 모델은 허용 + ERROR (fail-open, 조용한 통과 방지용 로그 필수).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiRequestGate {

    private static final String KEY_PREFIX = "ai:global:";
    private static final Duration KEY_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate stringRedisTemplate;
    private final OpenAiGateProperties properties;

    public sealed interface Decision {
        record Permitted() implements Decision {
        }

        record Rejected(Duration retryAfter) implements Decision {
        }
    }

    public Decision tryAcquire(String model, int estimatedTokens) {
        OpenAiGateProperties.ModelLimit limit = properties.models().get(model);
        if (limit == null) {
            log.error("전역 게이트에 모델 한도 미설정 — 검사 없이 허용 model={}", model);
            return new Decision.Permitted();
        }
        long epochMinute = Instant.now().getEpochSecond() / 60;
        String requestKey = KEY_PREFIX + model + ":rpm:" + epochMinute;
        String tokenKey = KEY_PREFIX + model + ":tpm:" + epochMinute;
        try {
            Long requestCount = incrementWithTtl(requestKey, 1L);
            Long tokenCount = incrementWithTtl(tokenKey, estimatedTokens);
            if (requestCount == null || tokenCount == null) {
                log.error("전역 게이트 INCRBY 응답 없음 — 검사 없이 허용 model={}", model);
                return new Decision.Permitted();
            }
            if (requestCount > limit.requestsPerMinute() || tokenCount > limit.tokensPerMinute()) {
                stringRedisTemplate.opsForValue().increment(requestKey, -1L);
                stringRedisTemplate.opsForValue().increment(tokenKey, (long) -estimatedTokens);
                return new Decision.Rejected(untilNextMinute());
            }
            return new Decision.Permitted();
        } catch (DataAccessException e) {
            log.error("전역 게이트 Redis 접근 실패 — 검사 없이 허용 model={}", model, e);
            return new Decision.Permitted();
        }
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
