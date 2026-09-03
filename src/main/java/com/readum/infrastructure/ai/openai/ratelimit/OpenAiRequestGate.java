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
 * 성공 시 실측 재보정은 하지 않는다 — 분 창은 금방 지나가고, 목적이 정밀 회계가 아니라 계정 429 예방이라서다.
 * 다만 생성 실패(예외·타임아웃)는 OpenAI 가 토큰을 소모하지 않았으므로, 호출 지점이 확보 때 받은
 * 계상 내역({@link GateReservation})을 {@link #compensate} 에 돌려줘 보상 차감할 수 있다.
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

    /** 계상 내역 — 보상 차감({@link #compensate})이 확보 시점의 분 키를 복원하는 데 필요한 전부. */
    public record GateReservation(String model, long epochMinute, int estimatedTokens) {
    }

    public sealed interface Decision {
        /** 분당 예산에 계상하고 통과 — 생성 실패 시 reservation 으로 보상 차감할 수 있다. */
        record Permitted(GateReservation reservation) implements Decision {
        }

        /** 계상 없이 통과(fail-open: 모델 한도 미설정·Redis 장애·INCRBY 응답 없음) — 보상할 계상이 없다. */
        record PermittedUncounted() implements Decision {
        }

        record Rejected(Duration retryAfter, RejectReason reason) implements Decision {
        }
    }

    public Decision tryAcquire(String model, int estimatedTokens) {
        OpenAiGateProperties.ModelLimit limit = properties.models().get(model);
        if (limit == null) {
            log.error("전역 게이트에 모델 한도 미설정 — 검사 없이 허용 model={}", model);
            return new Decision.PermittedUncounted();
        }
        try {
            Duration cooldown = remainingQuotaCooldown(model);
            if (cooldown != null) {
                // 계정 quota 소진 쿨다운 중 — 분당 카운터를 세지 않고 즉시 거절(감지 지점이 이미 열어 둠).
                return new Decision.Rejected(cooldown, RejectReason.QUOTA_COOLDOWN);
            }
            long epochMinute = Instant.now().getEpochSecond() / 60;
            String requestKey = requestCountKey(model, epochMinute);
            String tokenKey = tokenCountKey(model, epochMinute);
            Long requestCount = incrementWithTtl(requestKey, 1L);
            Long tokenCount = incrementWithTtl(tokenKey, estimatedTokens);
            if (requestCount == null || tokenCount == null) {
                log.error("전역 게이트 INCRBY 응답 없음 — 검사 없이 허용 model={}", model);
                return new Decision.PermittedUncounted();
            }
            if (requestCount > limit.requestsPerMinute() || tokenCount > limit.tokensPerMinute()) {
                stringRedisTemplate.opsForValue().increment(requestKey, -1L);
                stringRedisTemplate.opsForValue().increment(tokenKey, (long) -estimatedTokens);
                return new Decision.Rejected(untilNextMinute(), RejectReason.RATE_BUDGET);
            }
            return new Decision.Permitted(new GateReservation(model, epochMinute, estimatedTokens));
        } catch (DataAccessException e) {
            log.error("전역 게이트 Redis 접근 실패 — 검사 없이 허용 model={}", model, e);
            return new Decision.PermittedUncounted();
        }
    }

    /**
     * 생성 실패 시 보상 차감 — 확보 시점의 분 키에서 "요청 1 + 추정 토큰"을 되돌린다.
     * 반드시 예약이 담고 있는 분 키를 쓴다: 실패가 다음 분으로 넘어간 뒤 현재 분 키를 차감하면
     * 새 창을 오염시킨다. 이미 만료된 분 키에 DECRBY 하면 TTL 없는 음수 키가 새로 생기므로
     * 차감 후 항상 TTL 을 다시 건다 — 옛 분 키의 음수 값은 아무도 읽지 않고 TTL 로 소멸한다.
     * Redis 장애는 ERROR 로그 후 무시한다 — 보상 실패가 응답 경로를 막으면 안 되고,
     * 분 창 자연 소멸(최대 60초)이 안전망이다.
     */
    public void compensate(GateReservation reservation) {
        try {
            String requestKey = requestCountKey(reservation.model(), reservation.epochMinute());
            String tokenKey = tokenCountKey(reservation.model(), reservation.epochMinute());
            stringRedisTemplate.opsForValue().increment(requestKey, -1L);
            stringRedisTemplate.expire(requestKey, KEY_TTL);
            stringRedisTemplate.opsForValue().increment(tokenKey, (long) -reservation.estimatedTokens());
            stringRedisTemplate.expire(tokenKey, KEY_TTL);
        } catch (DataAccessException e) {
            log.error("전역 게이트 보상 차감 실패 — 분 창 자연 소멸에 맡김 model={} epochMinute={}",
                    reservation.model(), reservation.epochMinute(), e);
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

    private String requestCountKey(String model, long epochMinute) {
        return KEY_PREFIX + model + ":rpm:" + epochMinute;
    }

    private String tokenCountKey(String model, long epochMinute) {
        return KEY_PREFIX + model + ":tpm:" + epochMinute;
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
