package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 한 작업 처리의 트랜잭션 단계들. 비-TX 오케스트레이터(SummaryGenerationWorker)가 순서대로 호출한다.
 * 모든 변경 메서드는 작업 행을 비관적 락으로 잡고 lock_owner 일치(펜싱)를 확인한 뒤에만 반영한다 —
 * lease 만료로 재선점된 작업을 늦게 돌아온 옛 워커가 건드리지 못하게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryJobLifecycleService {

    private final SummaryJobRepository summaryJobRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryJobProperties properties;

    /**
     * 처리 대상 작업을 하나 선점한다. SKIP LOCKED 로 다른 워커와 겹치지 않는다.
     * owner 는 이 선점만의 토큰. 반환된 작업 id 를 워커가 이후 단계에 넘긴다. 없으면 null.
     */
    @Transactional
    public Long claimOne(String owner) {
        List<SummaryJob> candidates = summaryJobRepository.findClaimable(
                SummaryJob.Status.PENDING, LocalDateTime.now(), PageRequest.of(0, 1));
        if (candidates.isEmpty()) {
            return null;
        }
        SummaryJob job = candidates.get(0);
        job.claim(owner, LocalDateTime.now().plus(properties.lease()));
        return job.getId();
    }

    /**
     * 생성 준비. 작업 소유권 확인 → 세션이 ACTIVE 면 대화를 모아 컨텍스트 반환.
     * 세션이 ACTIVE 가 아니면(이미 종료/없음) 작업을 성공 처리하고 null 반환. 세션 상태는 바꾸지 않는다.
     */
    @Transactional
    public SummaryGenerationContext prepareGeneration(Long jobId, String owner) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            return null;
        }
        AiChatSession session = aiChatSessionRepository.findByIdForUpdate(job.getAiChatSessionId()).orElse(null);
        if (session == null || !session.getStatus().equals(AiChatSession.Status.ACTIVE)) {
            job.markSucceeded();
            return null;
        }
        List<AiChatMessage> messages =
                aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(session.getId());
        return new SummaryGenerationContext(session.getId(), session.getUserBookId(), messages);
    }

    /** 성공 기록 — 감상문 저장 + 세션 영구 잠금 + 작업 성공. 소유권/세션상태 재확인. */
    @Transactional
    public void recordSuccess(Long jobId, String owner, Long userBookId, SummaryDraftResult result) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("감상문 성공 기록 소유권 상실 jobId={}", jobId);
            return;
        }
        AiChatSession session = aiChatSessionRepository.findByIdForUpdate(job.getAiChatSessionId()).orElse(null);
        if (session == null || !session.getStatus().equals(AiChatSession.Status.ACTIVE)) {
            job.markSucceeded();
            return;
        }
        summaryRepository.save(Summary.createCompleted(
                userBookId, session.getId(), result.title(), result.body()));
        session.lock();
        job.markSucceeded();
    }

    /**
     * lease 만료된 고아 작업을 PENDING 으로 되돌린다(즉시 재선점 가능). 세션은 건드리지 않는다 —
     * 차단은 PROCESSING 의 유효 lease 가 사라지면 자동 해제되기 때문.
     * @return 회수한 작업 수
     */
    @Transactional
    public int reclaimOrphans(int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        List<SummaryJob> orphans = summaryJobRepository.findOrphaned(
                now, PageRequest.of(0, batchSize));
        orphans.forEach(job -> job.releaseAfterOrphan(now));
        return orphans.size();
    }

    /** 실패 기록 — 재시도 가능하고 상한 미만이면 백오프 재시도, 아니면 FAILED. 세션은 건드리지 않는다. */
    @Transactional
    public void recordFailure(
            Long jobId, String owner, boolean retryable,
            String errorCode, String errorMessage, LocalDateTime explicitRetryAt
    ) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("감상문 실패 기록 소유권 상실 jobId={}", jobId);
            return;
        }
        boolean canRetry = retryable && job.getAttemptCount() + 1 < properties.maxAttempts();
        if (canRetry) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextAttemptAt = explicitRetryAt != null
                    ? explicitRetryAt
                    : properties.nextAttemptFrom(now, job.getAttemptCount());
            job.scheduleRetry(nextAttemptAt, errorCode, errorMessage);
        } else {
            job.markFailed(errorCode, errorMessage);
            log.error("감상문 생성 최종 실패(재시도 소진 또는 회복 불가) jobId={} sessionId={} errorCode={} message={}",
                    jobId, job.getAiChatSessionId(), errorCode, errorMessage);
        }
    }

    /**
     * 페이싱 backpressure(예산 미확보) 또는 burst 429 로 호출을 못 보낸 작업을,
     * 시도 횟수 미증가(무벌점)로 PENDING 에 되돌린다. 소유권 펜싱으로 회수된 작업은 건드리지 않는다.
     */
    @Transactional
    public void releaseWithoutPenalty(Long jobId, String owner) {
        SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            log.warn("감상문 무벌점 반납 소유권 상실 jobId={}", jobId);
            return;
        }
        job.releaseAfterOrphan(LocalDateTime.now());
    }

}