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
 * 채팅 호출에 실을 컨텍스트를 조립한다: [누적 요약] + [요약 반영 지점 이후 최근 원문 대화]. 현재 진행 중인 USER 메시지는 호출자가 append.
 * 요약 반영 지점(summarized_up_to_message_id) 이후 메시지만 원문으로 싣고, 그 앞은 요약이 대체한다 — 중복도 구멍도 없다.
 * 최근 원문 대화는 newest-first 로 token_count 를 합산해 최대 토큰(안전핀)까지, 항상 USER 로 시작(턴 경계 정렬),
 * 마지막 턴은 최대 토큰을 넘어도 포함(빈 최근 원문 대화 방지). 요약이 밀리면 최대 토큰 안에서 오래된 턴부터 잘리는 우아한 열화.
 */
@Service
@RequiredArgsConstructor
public class AiChatHistorySearchService {

    // 토큰 예산이 실질 상한이므로, DB fetch 는 넉넉한 안전 개수로만 제한한다(최근 원문 최대 토큰을 항상 초과 커버).
    private static final int SAFETY_FETCH_LIMIT = 400;

    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatContextSummaryRepository aiChatContextSummaryRepository;
    private final AiChatProperties aiChatProperties;
    private final TokenCounter tokenCounter;

    public AssembledContext assembleContext(Long sessionId) {
        AiChatContextSummary summary = aiChatContextSummaryRepository.findBySessionId(sessionId).orElse(null);
        long summarizedUpToMessageId = AiChatContextSummary.lastSummarizedMessageIdOrZero(summary);
        String summaryContent = summary != null ? summary.getContent() : null;

        List<AiChatMessage> recentDesc = aiChatMessageRepository.findRecentForContextAssembly(
                sessionId, PageRequest.of(0, SAFETY_FETCH_LIMIT));

        // 요약 반영 지점 이후(id > summarizedUpToMessageId)의 원문만 최근 원문 대화 후보로 남긴다. 요약 반영 지점 이전은 요약이 커버한다.
        List<AiChatMessage> afterSummarizedUpToDesc = new ArrayList<>();
        for (AiChatMessage message : recentDesc) {
            if (message.getId() != null && message.getId() > summarizedUpToMessageId) {
                afterSummarizedUpToDesc.add(message);
            }
        }
        if (afterSummarizedUpToDesc.isEmpty()) {
            return new AssembledContext(summaryContent, List.of());
        }

        int maxRecentRawTokens = aiChatProperties.context().assemblyRecentRawMaxTokens();

        // newest-first 로 누적하다 최대 토큰 초과 직전에서 멈춘다. 단, 최소 1개는 담는다(마지막 턴 강제 포함).
        List<AiChatMessage> selectedDesc = new ArrayList<>();
        int running = 0;
        for (AiChatMessage message : afterSummarizedUpToDesc) {
            int tokens = messageTokens(message);
            if (!selectedDesc.isEmpty() && running + tokens > maxRecentRawTokens) {
                break;
            }
            selectedDesc.add(message);
            running += tokens;
        }

        // 턴 경계 정렬: 최근 원문 대화가 USER 로 시작하도록 가장 오래된 쪽 ASSISTANT 를 떼어낸다.
        List<AiChatMessage> alignedDesc = ChatTurnAligner.trimRecentToStartWithUser(selectedDesc);

        List<AiChatMessage> ascending = new ArrayList<>(alignedDesc);
        Collections.reverse(ascending);
        List<HistoryMessage> recentMessages = ascending.stream().map(HistoryMessage::from).toList();
        return new AssembledContext(summaryContent, recentMessages);
    }

    private int messageTokens(AiChatMessage message) {
        Integer stored = message.getTokenCount();
        return stored != null ? stored : tokenCounter.count(message.getContent());
    }
}
