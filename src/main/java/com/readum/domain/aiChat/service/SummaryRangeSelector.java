package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.dto.SummaryRange;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 요약 반영 지점 이후 원문(delta)에서 이번에 요약할 구간을 고르는 순수 계산기. DB·트랜잭션 없이 입력만으로 결과가 정해진다.
 * 두 단계로 나눈다:
 * 1. 남길 경계 — 최신 쪽 keep-recent-raw-tokens 만큼은 요약하지 않고 원문으로 남긴다(품질 장치). 전체가 그 이하면 요약 안 함.
 * 2. 호출 예산에 맞춰 자르기 — 남은 요약 후보가 한 호출 예산을 넘으면 오래된 쪽부터 잘라 이번 회차 분량을 제한한다(부분 전진).
 * 두 단계 끝은 모두 완결된 턴(ASSISTANT)에 맞춘다({@link ChatTurnAligner}).
 */
@Component
@RequiredArgsConstructor
public class SummaryRangeSelector {

    private final AiChatProperties aiChatProperties;
    private final ContextSummaryJobProperties jobProperties;
    private final TokenCounter tokenCounter;

    /**
     * @param delta                 요약 반영 지점 이후 원문(시간 오름차순)
     * @param previousSummaryTokens  이전 누적 요약의 토큰 수(호출 예산 계상용, 없으면 0)
     */
    public SummaryRange select(List<AiChatMessage> delta, int previousSummaryTokens) {
        int summarizeEnd = findKeepBoundary(delta);
        if (summarizeEnd <= 0) {
            return SummaryRange.none();
        }
        int chunkTokenBudget = jobProperties.maxRequestTokens()
                - previousSummaryTokens
                - aiChatProperties.context().summaryEstimatedOutputTokens();
        int end = limitToCallBudget(delta, summarizeEnd, chunkTokenBudget);
        return SummaryRange.of(List.copyOf(delta.subList(0, end)));
    }

    /**
     * 남길 경계: delta[0..반환값) 을 요약에 병합하고 delta[반환값..] 은 최근 원문 대화로 남긴다.
     * 최신 keep-recent-raw-tokens 만큼은 항상 원문으로 남기고, 요약 구간은 완결된 턴(ASSISTANT)에서 끝낸다.
     * 전체 delta 가 남길 예산 이하면 요약 반영 지점을 진전시키지 않도록 0 을 반환한다.
     */
    private int findKeepBoundary(List<AiChatMessage> delta) {
        int keepBudget = aiChatProperties.context().keepRecentRawTokens();
        int n = delta.size();
        int summarizeEnd = n;
        long recentTokenSum = 0;
        for (int i = n - 1; i >= 0; i--) {
            recentTokenSum += messageTokens(delta.get(i));
            summarizeEnd = i;
            if (recentTokenSum >= keepBudget) {
                break;
            }
        }
        if (recentTokenSum < keepBudget) {
            return 0;
        }
        return ChatTurnAligner.alignSummarizeEndToCompletedTurn(delta, summarizeEnd, 0);
    }

    /**
     * 이번 회차 요약 구간(delta[0..반환값))을 chunkTokenBudget 안으로 자른다(오래된 쪽부터). 남은 backlog 는 다음 작업이 이어 소화한다.
     * 워커 fail-fast 와 같은 토큰 기준(원문 content)으로 세어, 자른 청크가 fail-fast 를 다시 유발하지 않게 한다.
     * 최소 1개는 포함(진전 보장)하고, 부분 청크의 끝은 완결된 턴(ASSISTANT)에 맞춘다.
     */
    private int limitToCallBudget(List<AiChatMessage> delta, int summarizeEnd, int chunkTokenBudget) {
        long chunkTokenSum = 0;
        int end = 0;
        for (int i = 0; i < summarizeEnd; i++) {
            long withNext = chunkTokenSum + tokenCounter.count(delta.get(i).getContent());
            if (i > 0 && withNext > chunkTokenBudget) {
                break;
            }
            chunkTokenSum = withNext;
            end = i + 1;
        }
        if (end >= summarizeEnd) {
            return summarizeEnd;
        }
        return ChatTurnAligner.alignSummarizeEndToCompletedTurn(delta, end, 1);
    }

    private int messageTokens(AiChatMessage message) {
        Integer stored = message.getTokenCount();
        return stored != null ? stored : tokenCounter.count(message.getContent());
    }
}
