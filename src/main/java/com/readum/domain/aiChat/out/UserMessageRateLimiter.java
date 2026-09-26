package com.readum.domain.aiChat.out;

/**
 * 사용자별 메시지 폭주 가드 Port — 짧은 창 안의 전송 시도 횟수를 검사와 동시에 기록한다.
 * 구현은 Redis ZSET + Lua 스크립트 (infrastructure/redis). 검사와 기록이 하나의 원자 연산이라
 * 같은 사용자의 동시 요청이 전부 "0건" 시점에 통과하던 구 DB 카운트 방식의 경쟁이 없다.
 * 창 길이·허용 건수는 AiChatProperties.RateLimit (10초/5건) 을 따른다.
 */
public interface UserMessageRateLimiter {

    /**
     * 창 내 시도 1건을 소모한다. 한도 초과면 Denied, Redis 장애면 Bypassed(허용).
     * Allowed 로 소모된 슬롯은 뒤 단계(moderation 차단·예산 거절 등)에서 거절돼도 반환하지 않는다
     * — 폭주 차단이라는 목적상 시도 자체를 세는 것이 맞다.
     */
    Result tryConsume(Long userId);

    sealed interface Result {

        /** 통과 — 이번 시도가 창에 기록됐다. */
        record Allowed() implements Result {
        }

        /** 한도 초과 — 기록 없이 거절 (429 대상). */
        record Denied() implements Result {
        }

        /** Redis 장애 — 검사 없이 허용 (fail-open). */
        record Bypassed() implements Result {
        }
    }
}
