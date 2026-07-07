package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.model.aiChat.entity.AiChatContextSummary;
import com.readum.model.aiChat.repository.AiChatContextSummaryJobRepository;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import jakarta.persistence.LockTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 세션에 대한 컨텍스트 요약 작업을, "요약 반영 지점 이후 최근 원문 대화 토큰 합 > 임계값"일 때만 멱등하게 적재한다.
 * 같은 세션에 이미 활성 작업이 있으면 새로 만들지 않는다(active_session_id unique).
 * insert 는 REQUIRES_NEW 로 격리(ContextSummaryJobInserter)하므로, 동시 적재로 unique 위반이 나도
 * 호출자 트랜잭션을 오염시키지 않고 조용히 무시할 수 있다. (감상문 EnqueueSummaryJobService 와 동일 골격)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnqueueContextSummaryJobService {

    private final AiChatContextSummaryJobRepository jobRepository;
    private final AiChatContextSummaryRepository summaryRepository;
    private final AiChatMessageRepository messageRepository;
    private final ContextSummaryJobInserter inserter;
    private final AiChatProperties aiChatProperties;

    /**
     * 요약 반영 지점 이후 최근 원문 대화 토큰 합이 트리거 임계값을 넘으면 요약 작업을 적재한다.
     * 순간 경합(락 대기 실패, #92)만 짧은 backoff 로 구제하고, unique 위반은 catch 에서 멱등 처리한다.
     */
    @Retryable(
            retryFor = { CannotAcquireLockException.class, LockTimeoutException.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2.0, maxDelay = 1000))
    public void enqueueIfRecentMessagesExceedThreshold(Long sessionId) {
        long summarizedUpToMessageId = summaryRepository.findBySessionId(sessionId)
                .map(AiChatContextSummary::getSummarizedUpToMessageId)
                .orElse(0L);
        long recentTokenSum = messageRepository.sumRecentMessageTokens(sessionId, summarizedUpToMessageId);
        if (recentTokenSum <= aiChatProperties.context().summarizeTriggerTokenThreshold()) {
            return;
        }
        if (jobRepository.existsByActiveSessionId(sessionId)) {
            return;
        }
        try {
            inserter.insertPending(sessionId);
        } catch (DataIntegrityViolationException e) {
            // unique 경합으로 확인되면 멱등 skip, 그 외 무결성 위반은 재던져 드러낸다.
            if (jobRepository.existsByActiveSessionId(sessionId)) {
                log.debug("컨텍스트 요약 작업 적재 경합 — 이미 활성 작업 존재 sessionId={}", sessionId);
                return;
            }
            throw e;
        }
    }
}
