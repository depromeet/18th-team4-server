package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AssembledContext;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatContextSummary;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 채팅 호출에 실을 컨텍스트를 조립한다: [누적 요약] + [요약 경계 이후 원문 꼬리]. 현재 진행 중인 USER 메시지는 호출자가 append.
 * 요약 경계(summarized_until_message_id) 이후 메시지만 원문으로 싣고, 그 앞은 요약이 대체한다 — 중복도 구멍도 없다.
 * 꼬리는 newest-first 로 token_count 를 합산해 하드캡(안전핀)까지, 항상 USER 로 시작(턴 경계 정렬),
 * 마지막 턴은 예산을 넘어도 포함(빈 꼬리 방지). 요약이 밀리면 하드캡 안에서 오래된 턴부터 잘리는 우아한 열화.
 */
@Service
@RequiredArgsConstructor
public class AiChatHistorySearchService {

    // 토큰 예산이 실질 상한이므로, DB fetch 는 넉넉한 안전 개수로만 제한한다(하드캡을 항상 초과 커버).
    private static final int SAFETY_FETCH_LIMIT = 400;

    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatContextSummaryRepository aiChatContextSummaryRepository;
    private final AiChatProperties aiChatProperties;
    private final TokenCounter tokenCounter;

    public AssembledContext assembleContext(Long sessionId) {
        AiChatContextSummary summary = aiChatContextSummaryRepository.findBySessionId(sessionId).orElse(null);
        long boundary = summary != null ? summary.getSummarizedUntilMessageId() : 0L;
        String summaryContent = summary != null ? summary.getContent() : null;

        List<AiChatMessage> recentDesc = aiChatMessageRepository.findRecentForContextAssembly(
                sessionId, PageRequest.of(0, SAFETY_FETCH_LIMIT));

        // 요약 경계 이후(id > boundary)의 원문만 꼬리 후보로 남긴다. 경계 이전은 요약이 커버한다.
        List<AiChatMessage> afterBoundaryDesc = new ArrayList<>();
        for (AiChatMessage message : recentDesc) {
            if (message.getId() != null && message.getId() > boundary) {
                afterBoundaryDesc.add(message);
            }
        }
        if (afterBoundaryDesc.isEmpty()) {
            return new AssembledContext(summaryContent, List.of());
        }

        int hardCap = aiChatProperties.context().rawTailHardCapTokens();

        // newest-first 로 누적하다 하드캡 초과 직전에서 멈춘다. 단, 최소 1개는 담는다(마지막 턴 강제 포함).
        List<AiChatMessage> selectedDesc = new ArrayList<>();
        int running = 0;
        for (AiChatMessage message : afterBoundaryDesc) {
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
        List<HistoryMessage> rawTail = ascending.stream().map(HistoryMessage::from).toList();
        return new AssembledContext(summaryContent, rawTail);
    }

    private int messageTokens(AiChatMessage message) {
        Integer stored = message.getTokenCount();
        return stored != null ? stored : tokenCounter.count(message.getContent());
    }
}
