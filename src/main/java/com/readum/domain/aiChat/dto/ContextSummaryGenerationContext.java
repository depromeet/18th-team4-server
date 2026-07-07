package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

/**
 * 컨텍스트 요약 워커의 "준비" 트랜잭션이 모아 넘기는 생성 입력.
 * - previousSummaryContent: 이전 누적 요약(없으면 null — 첫 요약)
 * - previousVersion: 이전 요약의 version(없으면 null). 기록 시 낙관적 충돌 검증에 쓴다 —
 *   준비~기록 사이(LLM 호출 동안)에 다른 워커가 요약을 갱신했으면 이 계산은 폐기한다.
 * - deltaToSummarize: 이번에 요약에 새로 병합할 경계 이후 원문(시간 오름차순)
 * - newBoundaryMessageId: 갱신 후 요약이 커버할 마지막 메시지 id(항상 ASSISTANT, 단조 증가)
 */
public record ContextSummaryGenerationContext(
        Long sessionId,
        String previousSummaryContent,
        Integer previousVersion,
        List<AiChatMessage> deltaToSummarize,
        long newBoundaryMessageId
) {
}
