package com.readum.domain.aiChat.out;

import java.time.Duration;

/**
 * 사용자별 토큰 예산 Port — 선불 예약 + 실측 보정.
 * 구현은 Redis 카운터 (infrastructure/redis). rate limit 정책이 사용자별 도메인 규칙이 되어
 * Port 로 승격한 사례 (docs/conventions/service-and-port.md 참조).
 */
public interface ChatTokenBudget {

    /** 추정 토큰을 창 예산에서 선점한다. 한도 초과면 Denied, Redis 장애면 Bypassed(허용). */
    Result reserve(Long userId, int estimatedTokens);

    /** 실측(또는 부분 추정) 사용량으로 예약을 보정한다. */
    void settle(Long userId, Result.Granted reservation, int actualTotalTokens);

    sealed interface Result {
        record Granted(long windowStartEpochSecond, int reservedTokens) implements Result {
        }

        record Denied(Duration retryAfter) implements Result {
        }

        /** Redis 장애 — 예산 검사 없이 허용 (fail-open). settle 대상 아님. */
        record Bypassed() implements Result {
        }
    }
}
