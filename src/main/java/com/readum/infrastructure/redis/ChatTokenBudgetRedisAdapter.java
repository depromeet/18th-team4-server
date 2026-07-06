package com.readum.infrastructure.redis;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.out.ChatTokenBudget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * Redis 카운터 기반 토큰 예산. 키 = ai-chat:token-budget:{userId}:{창 시작 epoch초},
 * 값 = 창 내 사용(예약 포함) 토큰 합계. TTL 로 자동 만료되어 정리 작업이 없다.
 * Redis 장애 시 허용 + ERROR 로그 (fail-open) — 비용 방어 목적상 차단이 더 큰 피해라서다.
 * 단일 검사·기록 지점: 저장소를 교체(예: 고트래픽 시 설계 변경)해도 이 클래스 밖은 무변경.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTokenBudgetRedisAdapter implements ChatTokenBudget {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");
    private static final String KEY_PREFIX = "ai-chat:token-budget:";
    private static final Duration TTL_MARGIN = Duration.ofHours(1);

    private final StringRedisTemplate stringRedisTemplate;
    private final AiChatProperties aiChatProperties;

    @Override
    public Result reserve(Long userId, int estimatedTokens) {
        AiChatProperties.TokenBudget budget = aiChatProperties.tokenBudget();
        TokenBudgetWindow window = TokenBudgetWindow.current(Clock.system(ZONE_KST), budget.windowHours());
        String key = key(userId, window.startEpochSecond());
        try {
            Long accumulated = stringRedisTemplate.opsForValue().increment(key, estimatedTokens);
            if (accumulated == null) {
                log.error("토큰 예산 INCRBY 응답 없음 — 예산 검사 없이 허용 (fail-open) userId={}", userId);
                return new Result.Bypassed();
            }
            if (accumulated == (long) estimatedTokens) {
                // 이 창의 첫 기록 — 창 길이 + 여유만큼 뒤 자동 만료
                stringRedisTemplate.expire(key, Duration.ofHours(budget.windowHours()).plus(TTL_MARGIN));
            }
            if (accumulated > budget.tokensPerWindow()) {
                stringRedisTemplate.opsForValue().decrement(key, estimatedTokens);
                return new Result.Denied(window.untilNext());
            }
            return new Result.Granted(window.startEpochSecond(), estimatedTokens);
        } catch (DataAccessException e) {
            log.error("토큰 예산 Redis 접근 실패 — 예산 검사 없이 허용 (fail-open) userId={}", userId, e);
            return new Result.Bypassed();
        }
    }

    @Override
    public void settle(Long userId, Result.Granted reservation, int actualTotalTokens) {
        long delta = (long) actualTotalTokens - reservation.reservedTokens();
        if (delta == 0) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue()
                    .increment(key(userId, reservation.windowStartEpochSecond()), delta);
        } catch (DataAccessException e) {
            log.error("토큰 예산 보정 실패 — 예약치가 그대로 남는다 userId={} delta={}", userId, delta, e);
        }
    }

    private String key(Long userId, long windowStartEpochSecond) {
        return KEY_PREFIX + userId + ":" + windowStartEpochSecond;
    }
}
