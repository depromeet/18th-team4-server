package com.readum.domain.aiChat.dto;

import java.util.List;

/**
 * 채팅 호출에 실을 조립된 컨텍스트. 요약 반영 지점으로 두 부분을 정확히 나눈다(중복·구멍 없음):
 * - summary: 누적 요약(요약 반영 지점까지 커버). 없으면 null — 시스템 프롬프트에 저변동 블록으로 붙는다.
 * - recentMessages: 요약 반영 지점 이후의 원문 메시지 전부(하드캡 안전핀 안, 항상 USER 로 시작). 매 턴 변동 블록.
 */
public record AssembledContext(String summary, List<HistoryMessage> recentMessages) {
}
