package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.model.aiChat.entity.AiChatContextSummary;
import com.readum.model.aiChat.repository.AiChatContextSummaryJobRepository;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 세션에 대한 컨텍스트 요약 작업을, "요약 반영 지점 이후 최근 원문 대화 토큰 합 > 임계값"일 때만 멱등하게 적재한다.
 * 같은 세션에 이미 활성 작업이 있으면 새로 만들지 않는다(active_session_id unique).
 * insert 는 REQUIRES_NEW 로 격리(ContextSummaryJobInserter)하므로, 동시 적재로 unique 위반이 나도
 * 호출자 트랜잭션을 오염시키지 않고 조용히 무시할 수 있다.
 *
 * 감상문 EnqueueSummaryJobService 와 골격은 같지만 적재 트리거의 성격이 달라 재시도는 두지 않는다(docs/record/0007):
 * 이 적재는 ASSISTANT 응답 커밋 후 리스너가 비동기로 부르는 best-effort 작업이라, 순간 락 실패는 다음 턴 트리거가
 * 자가 치유한다. 감상문 적재는 사용자 대면 동기 one-shot(SummaryDraftService)이라 "지금 실패=요청 실패"여서 재시도가 정당하다.
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
     * unique 위반은 catch 에서 멱등 처리한다. 순간 락 실패(#92)는 재시도하지 않는다 —
     * best-effort 라 다음 턴 트리거가 자가 치유하고, #92 의 gap lock 경합은 워커 claimOne 의 READ COMMITTED 로
     * 이미 완화됐으며, 재시도 backoff 가 공유 boundedElastic 스레드를 무는 비용이 이득보다 크다(docs/record/0007).
     */
    public void enqueueIfRecentMessagesExceedThreshold(Long sessionId) {
        // 트리거도 체크포인트(마지막 요약 메시지 id)가 필요하다 — "그 이후 원문 토큰 합"으로 판정하기 때문. 읽기는 조립기·선택기와 같은 접근자로 통일.
        AiChatContextSummary summary = summaryRepository.findBySessionId(sessionId).orElse(null);
        long lastSummarizedMessageId = AiChatContextSummary.lastSummarizedMessageIdOrZero(summary);
        long recentTokenSum = messageRepository.sumRecentMessageTokens(sessionId, lastSummarizedMessageId);
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
