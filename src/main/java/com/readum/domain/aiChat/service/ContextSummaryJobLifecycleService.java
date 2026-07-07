package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.ContextSummaryGenerationContext;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.domain.aiChat.dto.SummaryRange;
import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatContextSummary;
import com.readum.model.aiChat.entity.AiChatContextSummaryJob;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatContextSummaryJobRepository;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 한 컨텍스트 요약 작업 처리의 트랜잭션 단계들. 비-TX 오케스트레이터(ContextSummaryWorker)가 순서대로 호출하고,
 * 그 사이(트랜잭션 밖)에서 LLM 을 부른다. 감상문 워커 골격을 복제하되, 세션 잠금 대신 요약 행을 낙관적으로 갱신한다.
 * 모든 변경 메서드는 작업 행을 비관적 락 + lock_owner 펜싱으로 잡아, 재선점된 작업을 늦게 돌아온 옛 워커가 건드리지 못하게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextSummaryJobLifecycleService {

    private final AiChatContextSummaryJobRepository jobRepository;
    private final AiChatContextSummaryRepository summaryRepository;
    private final AiChatMessageRepository messageRepository;
    private final ContextSummaryJobProperties jobProperties;
    private final TokenCounter tokenCounter;
    private final SummaryRangeSelector summaryRangeSelector;

    /**
     * 처리 대상 작업을 하나 선점한다. SKIP LOCKED 로 다른 워커와 겹치지 않는다. 없으면 null.
     * READ COMMITTED 인 이유는 감상문 큐와 동일 — 범위 스캔의 gap lock 이 신규 PENDING INSERT 와 충돌하는 것을 피한다(#92).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long claimOne(String owner) {
        List<AiChatContextSummaryJob> candidates = jobRepository.findClaimable(
                AiChatContextSummaryJob.Status.PENDING, LocalDateTime.now(), PageRequest.of(0, 1));
        if (candidates.isEmpty()) {
            return null;
        }
        AiChatContextSummaryJob job = candidates.get(0);
        job.claim(owner, LocalDateTime.now().plus(jobProperties.lease()));
        return job.getId();
    }

    /**
     * 생성 준비. 소유권 확인 → 현재 요약(version V, 요약 반영 지점 B) + 요약 반영 지점 이후 원문 로드 → 요약 범위를 처리 시점에 계산한다.
     * 범위: 요약 반영 지점 B 부터, 최신 메시지 중 keep-recent-raw-tokens 를 남긴 지점(가장 가까운 턴 경계로 내림)까지.
     * 남길 원문이 예산 이하라 요약할 게 없으면(요약 반영 지점 진전 없음) null 을 반환하고 작업을 성공 처리한다.
     * 요약 대상 원문이 한 호출 예산(maxRequestTokens)을 넘으면 오래된 쪽부터 예산만큼만 잘라 부분 전진한다 — 나머지 backlog 는 다음 작업이 이어 소화(영구 동결 방지).
     */
    @Transactional
    public ContextSummaryGenerationContext prepareGeneration(Long jobId, String owner) {
        AiChatContextSummaryJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            return null;
        }
        Long sessionId = job.getSessionId();
        AiChatContextSummary summary = summaryRepository.findBySessionId(sessionId).orElse(null);
        long summarizedUpToMessageId = AiChatContextSummary.lastSummarizedMessageIdOrZero(summary);

        List<AiChatMessage> delta = messageRepository.findCompletedMessagesAfter(sessionId, summarizedUpToMessageId);
        int previousSummaryTokens = summary != null ? tokenCounter.count(summary.getContent()) : 0;
        SummaryRange range = summaryRangeSelector.select(delta, previousSummaryTokens);
        if (range.isEmpty()) {
            // 남길 원문이 keep-recent-raw-tokens 이하이거나 완결된 턴이 없다 — 요약 반영 지점을 진전시키지 않고 성공 종료.
            job.markSucceeded();
            return null;
        }
        return new ContextSummaryGenerationContext(
                sessionId,
                summary != null ? summary.getContent() : null,
                summary != null ? summary.getVersion() : null,
                range.messagesToSummarize(),
                range.lastSummarizedMessageId());
    }

    /**
     * 성공 기록 — 낙관적 갱신. 준비 때 본 version 과 현재 version 이 일치하고 요약 반영 지점이 단조 증가할 때만 반영한다.
     * 늦게 돌아온 옛 워커의 계산(그 사이 다른 워커가 요약을 갱신)은 폐기하고 작업만 성공 종료한다.
     */
    @Transactional
    public void recordSuccess(
            Long jobId, String owner, ContextSummaryGenerationContext context, ContextSummaryResult result
    ) {
        AiChatContextSummaryJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("컨텍스트 요약 성공 기록 소유권 상실 jobId={}", jobId);
            return;
        }
        int tokenCount = tokenCounter.count(result.content());
        AiChatContextSummary existing = summaryRepository.findBySessionIdForUpdate(context.sessionId()).orElse(null);
        if (existing == null) {
            if (context.previousVersion() != null) {
                // 준비 땐 요약이 있었는데 지금 없다 — 있을 수 없는 상태. 보수적으로 폐기.
                log.warn("컨텍스트 요약 갱신 폐기(요약 행 사라짐) sessionId={}", context.sessionId());
                job.markSucceeded();
                return;
            }
            summaryRepository.save(AiChatContextSummary.create(
                    context.sessionId(), result.content(), context.lastSummarizedMessageId(), tokenCount));
            job.markSucceeded();
            return;
        }
        boolean versionMatches = context.previousVersion() != null
                && existing.getVersion() == context.previousVersion();
        boolean advancesSummarizedUpTo = context.lastSummarizedMessageId() > existing.getSummarizedUpToMessageId();
        if (!versionMatches || !advancesSummarizedUpTo) {
            // 낙관적 충돌 또는 요약 반영 지점 역행 — 이 계산은 이미 낡았다. 재시도해도 같은 낡은 입력이라 폐기하고 성공 종료.
            log.info("컨텍스트 요약 갱신 폐기(낙관적 충돌) sessionId={} preparedVersion={} currentVersion={}",
                    context.sessionId(), context.previousVersion(), existing.getVersion());
            job.markSucceeded();
            return;
        }
        existing.applyUpdate(result.content(), context.lastSummarizedMessageId(), tokenCount);
        job.markSucceeded();
    }

    /**
     * lease 만료된 고아 작업을 PENDING 으로 되돌린다(즉시 재선점 가능). RC 이유는 감상문 큐와 동일(#92).
     * @return 회수한 작업 수
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int reclaimOrphans(int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        List<AiChatContextSummaryJob> orphans = jobRepository.findOrphaned(now, PageRequest.of(0, batchSize));
        orphans.forEach(job -> job.releaseAfterOrphan(now));
        return orphans.size();
    }

    /** 실패 기록 — 재시도 가능하고 상한 미만이면 백오프 재시도, 아니면 FAILED. */
    @Transactional
    public void recordFailure(
            Long jobId, String owner, boolean retryable,
            String errorCode, String errorMessage, LocalDateTime explicitRetryAt
    ) {
        AiChatContextSummaryJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("컨텍스트 요약 실패 기록 소유권 상실 jobId={}", jobId);
            return;
        }
        boolean canRetry = retryable && job.getAttemptCount() + 1 < jobProperties.maxAttempts();
        if (canRetry) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextAttemptAt = explicitRetryAt != null
                    ? explicitRetryAt
                    : jobProperties.nextAttemptFrom(now, job.getAttemptCount());
            job.scheduleRetry(nextAttemptAt, errorCode, errorMessage);
        } else {
            job.markFailed(errorCode, errorMessage);
            log.error("컨텍스트 요약 최종 실패(재시도 소진 또는 회복 불가) jobId={} sessionId={} errorCode={} message={}",
                    jobId, job.getSessionId(), errorCode, errorMessage);
        }
    }

    /** 게이트 backpressure(burst 429)로 호출을 못 보낸 작업을 시도 횟수 미증가(무벌점)로 PENDING 에 되돌린다. */
    @Transactional
    public void releaseWithoutPenalty(Long jobId, String owner) {
        AiChatContextSummaryJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("컨텍스트 요약 무벌점 반납 소유권 상실 jobId={}", jobId);
            return;
        }
        job.releaseAfterOrphan(LocalDateTime.now());
    }
}
