package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.ContextSummaryGenerationContext;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
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
    private final AiChatProperties aiChatProperties;
    private final TokenCounter tokenCounter;

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
     * 생성 준비. 소유권 확인 → 현재 요약(version V, 경계 B) + 경계 이후 원문 로드 → 요약 범위를 처리 시점에 계산한다.
     * 범위: 경계 B 부터, 최신 메시지 중 recent-raw-token-budget 을 남긴 지점(가장 가까운 턴 경계로 내림)까지.
     * 남길 원문이 예산 이하라 요약할 게 없으면(경계 진전 없음) null 을 반환하고 작업을 성공 처리한다.
     */
    @Transactional
    public ContextSummaryGenerationContext prepareGeneration(Long jobId, String owner) {
        AiChatContextSummaryJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            return null;
        }
        Long sessionId = job.getSessionId();
        AiChatContextSummary summary = summaryRepository.findBySessionId(sessionId).orElse(null);
        long boundary = summary != null ? summary.getSummarizedUntilMessageId() : 0L;

        List<AiChatMessage> delta = messageRepository.findCompletedAfterIdAsc(sessionId, boundary);
        int splitIndex = computeSummarizeSplit(delta);
        if (splitIndex <= 0) {
            // 남길 원문이 recent-raw-token-budget 이하 — 아직 요약할 구간이 없다. 경계를 진전시키지 않고 성공 종료.
            job.markSucceeded();
            return null;
        }
        List<AiChatMessage> toSummarize = List.copyOf(delta.subList(0, splitIndex));
        long newBoundary = toSummarize.get(toSummarize.size() - 1).getId();
        return new ContextSummaryGenerationContext(
                sessionId,
                summary != null ? summary.getContent() : null,
                summary != null ? summary.getVersion() : null,
                toSummarize,
                newBoundary);
    }

    /**
     * 요약 범위의 split 인덱스를 계산한다: delta[0..split) 를 요약에 병합하고 delta[split..] 는 원문 꼬리로 남긴다.
     * 최신 메시지 중 recent-raw-token-budget 만큼은 항상 원문으로 남기고(품질 장치), 요약 경계는 항상 완결된 턴의 끝(ASSISTANT)에 둔다.
     * 요약할 구간이 없으면 0(호출자가 skip).
     */
    private int computeSummarizeSplit(List<AiChatMessage> delta) {
        int recentRawBudget = aiChatProperties.context().recentRawTokenBudget();
        int n = delta.size();
        int split = n;
        long tailTokens = 0;
        for (int i = n - 1; i >= 0; i--) {
            tailTokens += messageTokens(delta.get(i));
            split = i;
            if (tailTokens >= recentRawBudget) {
                break;
            }
        }
        // 전체 델타가 예산 이하면 남길 게 없어 요약 구간이 곧 전부가 되면 안 된다 → 요약하지 않는다(경계 진전 없음).
        if (tailTokens < recentRawBudget) {
            return 0;
        }
        // 턴 경계 정렬: 요약 구간의 끝은 ASSISTANT 여야 원문 꼬리가 USER 로 시작한다.
        // delta[split-1] 이 USER 면 그 USER 를 원문 꼬리로 밀어 요약 구간이 ASSISTANT 로 끝나게 한다.
        while (split > 0 && delta.get(split - 1).getRole() == AiChatMessage.Role.USER) {
            split -= 1;
        }
        return split;
    }

    /**
     * 성공 기록 — 낙관적 갱신. 준비 때 본 version 과 현재 version 이 일치하고 경계가 단조 증가할 때만 반영한다.
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
                    context.sessionId(), result.content(), context.newBoundaryMessageId(), tokenCount));
            job.markSucceeded();
            return;
        }
        boolean versionMatches = context.previousVersion() != null
                && existing.getVersion() == context.previousVersion();
        boolean advancesBoundary = context.newBoundaryMessageId() > existing.getSummarizedUntilMessageId();
        if (!versionMatches || !advancesBoundary) {
            // 낙관적 충돌 또는 경계 역행 — 이 계산은 이미 낡았다. 재시도해도 같은 낡은 입력이라 폐기하고 성공 종료.
            log.info("컨텍스트 요약 갱신 폐기(낙관적 충돌) sessionId={} preparedVersion={} currentVersion={}",
                    context.sessionId(), context.previousVersion(), existing.getVersion());
            job.markSucceeded();
            return;
        }
        existing.applyUpdate(result.content(), context.newBoundaryMessageId(), tokenCount);
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

    private int messageTokens(AiChatMessage message) {
        Integer stored = message.getTokenCount();
        return stored != null ? stored : tokenCounter.count(message.getContent());
    }
}
