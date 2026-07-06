package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 채팅 호출에 실을 이전 이력을, 토큰 예산 기반 원문 꼬리로 고른다(현재 진행 중인 USER 메시지는 호출자가 append).
 * newest-first 로 token_count 를 합산해 hard-cap 까지, 꼬리는 항상 USER 로 시작(턴 경계 정렬),
 * 마지막 턴은 예산을 넘어도 포함(빈 꼬리 방지). 요약 결합은 PR-3.
 */
@Service
@RequiredArgsConstructor
public class AiChatHistorySearchService {

    // 토큰 예산이 실질 상한이므로, DB fetch 는 넉넉한 안전 개수로만 제한한다(hard-cap 8k 를 항상 초과 커버).
    private static final int SAFETY_FETCH_LIMIT = 400;

    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatProperties aiChatProperties;
    private final TokenCounter tokenCounter;

    public List<HistoryMessage> findPreviousHistory(Long sessionId) {
        List<AiChatMessage> recentDesc = aiChatMessageRepository.findRecentForContextAssembly(
                sessionId, PageRequest.of(0, SAFETY_FETCH_LIMIT));
        if (recentDesc.isEmpty()) {
            return List.of();
        }
        int hardCap = aiChatProperties.context().rawTailHardCapTokens();

        // newest-first 로 누적하다 hard-cap 초과 직전에서 멈춘다. 단, 최소 1개는 담는다(마지막 턴 강제 포함).
        List<AiChatMessage> selectedDesc = new ArrayList<>();
        int running = 0;
        for (AiChatMessage message : recentDesc) {
            int tokens = messageTokens(message);
            if (!selectedDesc.isEmpty() && running + tokens > hardCap) {
                break;
            }
            selectedDesc.add(message);
            running += tokens;
        }

        // 턴 경계 정렬: 꼬리는 USER 로 시작해야 한다. 가장 오래된(선택 리스트의 끝) 쪽이 ASSISTANT 면 제거.
        while (selectedDesc.size() > 1
                && selectedDesc.get(selectedDesc.size() - 1).getRole() == AiChatMessage.Role.ASSISTANT) {
            selectedDesc.remove(selectedDesc.size() - 1);
        }

        List<AiChatMessage> ascending = new ArrayList<>(selectedDesc);
        Collections.reverse(ascending);
        return ascending.stream().map(HistoryMessage::from).toList();
    }

    private int messageTokens(AiChatMessage message) {
        Integer stored = message.getTokenCount();
        return stored != null ? stored : tokenCounter.count(message.getContent());
    }
}
