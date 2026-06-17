package com.readum.domain.aiChat.out;

import java.time.Duration;

/**
 * OpenAI 호출 전역 차단 스위치.
 * quota 소진처럼 계정 전역 문제일 때 일정 시간 호출을 멈춰, 작업마다 헛호출하는 것을 막는다.
 * 외부 상태에 대한 임시 판단이라 영속하지 않는다(잃어도 다음 호출이 다시 감지) — 단일 인스턴스는 in-memory 로 충분.
 * 다중 인스턴스에서 전역 공유가 필요하면 Redis TTL / DB 어댑터로 교체한다(Port 유지).
 */
public interface AiCallCircuitBreaker {

    /** 지금 호출이 차단된 상태인가. */
    boolean isOpen();

    /** 지금부터 duration 동안 호출을 차단한다. */
    void openFor(Duration duration);
}
