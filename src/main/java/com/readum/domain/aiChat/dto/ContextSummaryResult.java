package com.readum.domain.aiChat.dto;

/**
 * 컨텍스트 요약 LLM 호출 결과. 증분 방식(이전 요약 + 델타 원문 → 완전한 새 누적 요약)의 산출물.
 * 길이는 프롬프트 지시가 아니라 고정 섹션 틀 + 병합 지침으로 자연히 묶인다(스펙 8절).
 */
public record ContextSummaryResult(String content) {
}
