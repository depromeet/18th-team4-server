package com.readum.domain.aiChat.service;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 턴 경계 정렬 규칙을 한곳에 모은 순수 헬퍼. 한 턴은 USER→ASSISTANT 이므로, 요약 구간과 최근 원문 대화의
 * 경계는 완결된 턴에서 나눠야 반쪽 턴이 안 생긴다. 요약 구간은 ASSISTANT 로 끝나고, 최근 원문 대화는 USER 로 시작한다.
 * DB·상태 없이 리스트만 다룬다.
 */
final class ChatTurnAligner {

    private ChatTurnAligner() {
    }

    /**
     * 요약 구간(ascending[0..endExclusive))의 끝을 완결된 턴에 맞춘다. 끝이 USER 면 그 USER 를 최근 원문 쪽으로
     * 밀어 요약 구간이 ASSISTANT 로 끝나게 한다. minEnd 아래로는 내려가지 않는다(진전 보장이 필요한 부분 청크는 1).
     *
     * @param ascending    시간 오름차순 메시지
     * @param endExclusive 요약 구간의 끝(배타적)
     * @param minEnd       끝을 당길 수 있는 하한(0=전부 밀어낼 수 있음, 1=최소 1개는 남김)
     * @return 정렬된 끝(배타적)
     */
    static int alignSummarizeEndToCompletedTurn(List<AiChatMessage> ascending, int endExclusive, int minEnd) {
        int end = endExclusive;
        while (end > minEnd && ascending.get(end - 1).getRole() == AiChatMessage.Role.USER) {
            end -= 1;
        }
        return end;
    }

    /**
     * 최근 원문 대화(newest-first)가 USER 로 시작하도록, 가장 오래된 쪽(리스트의 끝)의 ASSISTANT 를 떼어낸다.
     * 토큰 예산으로 자르면서 오래된 USER 가 잘려 ASSISTANT 가 맨 앞(가장 오래된)으로 남는 경우를 정리한다.
     * 최소 1개는 남긴다(빈 최근 원문 대화 방지).
     *
     * @param recentNewestFirst 최신 우선 정렬된 최근 원문 대화
     * @return 가장 오래된 쪽이 USER 로 시작하도록 다듬은 새 리스트
     */
    static List<AiChatMessage> trimRecentToStartWithUser(List<AiChatMessage> recentNewestFirst) {
        List<AiChatMessage> trimmed = new ArrayList<>(recentNewestFirst);
        while (trimmed.size() > 1 && trimmed.get(trimmed.size() - 1).getRole() == AiChatMessage.Role.ASSISTANT) {
            trimmed.remove(trimmed.size() - 1);
        }
        return trimmed;
    }
}
