package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

/**
 * 채팅 컨텍스트 누적 요약 생성 포트. 증분 방식: 이전 요약 + 경계 이후 델타 원문 → 완전한 새 누적 요약.
 * 구현체(infrastructure)가 전역 게이트·감사 로그·프롬프트 조립을 담당한다.
 */
public interface AiContextSummaryClient {

    /**
     * @param previousSummary 이전 누적 요약(없으면 null/빈 문자열 — 첫 요약)
     * @param deltaMessages   요약 경계 이후 새로 요약할 원문 메시지(시간 오름차순)
     */
    ContextSummaryResult generate(String previousSummary, List<AiChatMessage> deltaMessages);
}
