package com.readum.domain.aiChat.event;

/**
 * ASSISTANT 응답이 COMPLETED 로 영속화·커밋된 뒤 발행 — 컨텍스트 요약이 필요한지 판정할 트리거.
 * 리스너가 요약 경계 이후 원문 꼬리 토큰 합이 임계값을 넘는지 보고, 넘으면 요약 job 을 멱등 적재한다.
 */
public record ContextSummarizeTriggerEvent(Long sessionId) {
}
