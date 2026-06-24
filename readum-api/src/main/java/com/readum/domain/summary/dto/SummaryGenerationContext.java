package com.readum.domain.summary.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

/**
 * 워커가 한 작업을 처리할 때, "준비" 트랜잭션이 모아 넘기는 생성 입력.
 * 세션 식별자 + 감상문 귀속용 userBookId + 요약에 넣을 전체 대화.
 */
public record SummaryGenerationContext(
        Long sessionId,
        Long userBookId,
        List<AiChatMessage> messages
) {
}
