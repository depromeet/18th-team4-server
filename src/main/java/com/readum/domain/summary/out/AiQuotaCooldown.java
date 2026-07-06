package com.readum.domain.summary.out;

/**
 * 계정 quota 소진 쿨다운 조회(읽기 전용). 워커가 job 선점 전에 확인해, 쿨다운 중엔 헛선점·attempt 소진을 피한다.
 * 쿨다운 진입(쓰기)은 감지 지점(OpenAiResponseErrorHandler)이 게이트에 직접 하므로 여기엔 없다.
 * 구현은 전역 게이트(OpenAiRequestGate)의 Redis 쿨다운 키를 공유한다 — 프로세스 간 상태 공유(#88).
 */
public interface AiQuotaCooldown {

    /** 지금 계정 quota 쿨다운 중인가. */
    boolean isCoolingDown();
}
