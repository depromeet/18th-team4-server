package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryBatchBuildItem;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.OpenAiBatch;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.OpenAiBatchRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final OpenAiBatchRepository openAiBatchRepository;
    private final SummaryJobProperties properties;

    /**
     * 처리 대상 작업을 하나 선점한다. SKIP LOCKED 로 다른 워커와 겹치지 않는다.
     * executionMode 로 SYNC/BATCH 를 분리해 동기 워커가 BATCH 큐를 침범하지 않는다.
     * owner 는 이 선점만의 토큰. 반환된 작업 id 를 워커가 이후 단계에 넘긴다. 없으면 null.
     */
    @Transactional
    public Long claimOne(SummaryJob.ExecutionMode executionMode, String owner) {
        List<SummaryJob> candidates = summaryJobRepository.findClaimable(
                executionMode, SummaryJob.Status.PENDING, LocalDateTime.now(),
                PageRequest.of(0, 1));
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
        }
    }

    /**
     * BATCH PENDING 작업을 maxJobs 만큼 후보로 선점(BATCH_BUILDING)하고 빌드 아이템 리스트를 반환한다.
     * 세션이 없거나 ACTIVE 가 아니면 해당 작업을 성공 처리하고 목록에서 제외한다(대화가 없으니 생성 불필요).
     * <p>
     * 루프 안에서 작업마다 세션·메시지를 각각 조회하는 것은 의도적인 구조다.
     * findClaimableBatch 가 SKIP LOCKED 으로 한 번에 여러 행을 선점한 뒤, 각 작업에 대해
     * claimOne + prepareGeneration 의 단계(세션 상태 확인 → 메시지 수집 → BATCH_BUILDING 전이)를
     * 한 트랜잭션 안에서 N 번 반복하는 패턴이다. N+1 문제가 아니라 "단일 작업 점유(claimOne)" 를
     * 청크 크기만큼 한 트랜잭션으로 묶은 것이므로 쿼리 수가 늘어나는 것은 설계 의도다.
     * </p>
     */
    @Transactional
    public List<SummaryBatchBuildItem> claimBatchChunk(String owner, int maxJobs, Duration buildLease) {
        LocalDateTime now = LocalDateTime.now();
        List<SummaryJob> candidates = summaryJobRepository.findClaimableBatch(
                now, PageRequest.of(0, maxJobs));
        List<SummaryBatchBuildItem> buildItems = new ArrayList<>();
        for (SummaryJob job : candidates) {
            AiChatSession session = aiChatSessionRepository.findByIdForUpdate(
                    job.getAiChatSessionId()).orElse(null);
            if (session == null || session.getStatus() != AiChatSession.Status.ACTIVE) {
                // 종료/부재 세션은 생성 불필요 — 무해 종료
                job.markSucceeded();
                continue;
            }
            job.startBatchBuilding(owner, now.plus(buildLease));
            List<AiChatMessage> messages =
                    aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(session.getId());
            buildItems.add(new SummaryBatchBuildItem(
                    job.getId(), job.getAiChatSessionId(), session.getUserBookId(), messages));
        }
        return buildItems;
    }

    /**
     * 제출 성공 기록 — openai_batch 행을 저장하고, 이 owner 가 점유 중인 작업들을 SUBMITTED 로 전이한다.
     * isOwnedBy 펜싱: lease 만료로 회수된 작업은 소유권 불일치로 건너뛴다.
     */
    @Transactional
    public void recordSubmission(List<Long> jobIds, String owner, String batchId, String inputFileId) {
        OpenAiBatch batch = openAiBatchRepository.save(
                OpenAiBatch.createSubmitted(batchId, inputFileId, jobIds.size()));
        for (Long jobId : jobIds) {
            SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job != null && job.isOwnedBy(owner)) {
                job.markSubmitted(batch.getId());
            }
        }
    }

    /**
     * 제출 실패 시 BATCH_BUILDING 점유를 PENDING 으로 되돌린다(시도 횟수 미증가 — 재청킹 대상).
     * isOwnedBy 펜싱: 이미 회수된 작업은 건드리지 않는다.
     */
    @Transactional
    public void releaseBuilding(List<Long> jobIds, String owner) {
        LocalDateTime now = LocalDateTime.now();
        for (Long jobId : jobIds) {
            SummaryJob job = summaryJobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job != null && job.isOwnedBy(owner)) {
                job.releaseAfterOrphan(now);
            }
        }
    }

}
