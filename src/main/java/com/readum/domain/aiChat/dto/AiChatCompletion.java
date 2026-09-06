package com.readum.domain.aiChat.dto;

/**
 * 정상 완료로 판정된 채팅 턴 하나의 결과 — 메시지 행에 남길 본문과 공급자가 준 사용량이다.
 * 스트리밍으로 받은 조각을 이어붙인 최종 본문을 담으며, 저장 계층이 이 값만 보고 행을 만든다.
 */
public record AiChatCompletion(
        String content,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {
}
