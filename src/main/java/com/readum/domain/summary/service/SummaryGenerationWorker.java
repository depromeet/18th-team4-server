package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiCallCircuitBreaker;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.domain.summary.out.SummaryCallRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 한 워커 스레드의 작업 처리 루프. 트랜잭션 없이, 각 트랜잭션 단계(SummaryJobLifecycleService)를
 * 순서대로 호출하고 그 사이(트랜잭션 밖)에서 OpenAI 를 부른다.
 *
 * 호출 전: quota 전역 차단이 열려 있으면 작업을 아예 선점하지 않는다. 그리고 OpenAI 호출 속도 제한기
 * (rateLimiter)로 분당 허용량(요청 수·토큰 수) 안에서만 호출하도록 조인다.
 * 이번 분 허용량이 다 차서 제한시간 안에 확보하지 못하면, 작업을 잡은 채 오래 기다리지 않고 큐로 되돌린다.
 * 오래 기다리면 점유 시한(lease, 5분)이 지나 회수기(reaper)가 같은 작업을 다른 워커에 넘겨
 * OpenAI 를 두 번 부를 수 있기 때문이다.
 * 실패 분류: quota → 전역 차단 후 재시도 / 429 burst → Retry-After 재시도 / 4xx → 즉시 FAILED / 5xx·기타 → 재시도.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerationWorker {

    private final SummaryJobLifecycleService lifecycleService;
    private final AiSummaryClient aiSummaryClient;
    private final AiCallCircuitBreaker circuitBreaker;
    private final SummaryCallRateLimiter rateLimiter;
    private final SummaryTokenEstimator tokenEstimator;
    private final SummaryJobProperties properties;

    /** 처리할 작업이 없을 때까지 계속 선점·처리한다. */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /**
     * 한 작업만 시도. 처리했으면 true, 더 처리할 게 없으면 false.
     * 전역 차단(quota)이 열려 있으면 작업을 선점하지 않고 false — 차단 중엔 아무 작업도 잡지 않는다.
     */
    public boolean processOne() {
        if (circuitBreaker.isOpen()) {
            return false;
        }
        String owner = UUID.randomUUID().toString();
        Long jobId = lifecycleService.claimOne(owner);
        if (jobId == null) {
            return false;
        }
        runJob(jobId, owner);
        return true;
    }

    private void runJob(Long jobId, String owner) {
        SummaryGenerationContext context = lifecycleService.prepareGeneration(jobId, owner);
        if (context == null) {
            return;
        }

        boolean acquired;
        try {
            int estimatedTokens = tokenEstimator.estimate(context.messages(), properties.reservedOutputTokens());
            acquired = rateLimiter.tryAcquire(estimatedTokens);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requeueForPacing(jobId, owner);
            return;
        }
        if (!acquired) {
            // 이번 분 호출 허용량이 다 차서 확보 실패 — 작업을 잡은 채 기다리지 않고 큐로 되돌린다(중복 호출 방지).
            requeueForPacing(jobId, owner);
            return;
        }

        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(context.messages());
        } catch (TooManyRequestsException e) {
            boolean quotaExhausted = e.getErrorCode() == AiChatErrorCode.AI_QUOTA_EXHAUSTED;
            if (quotaExhausted) {
                // 계정 전역 문제 — 일정 시간 전체 호출을 멈춘다.
                circuitBreaker.openFor(properties.breakerOpen());
            }
            lifecycleService.recordFailure(jobId, owner, true,
                    e.getErrorCode().name(), e.getMessage(), retryAtFrom(e.getRateLimitInfo()));
            return;
        } catch (NonTransientAiException e) {
            // 429 외 4xx — 재시도해도 같은 실패. 즉시 종료한다.
            log.warn("감상문 생성 회복 불가 오류 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, false,
                    AiChatErrorCode.AI_PROVIDER_ERROR.name(), e.getMessage(), null);
            return;
        } catch (TransientAiException e) {
            // 5xx — 일시 오류. 재시도한다.
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        } catch (Exception e) {
            // 알 수 없는 오류 — 보수적으로 재시도(작업 유실 방지).
            log.warn("감상문 생성 실패 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        }
        lifecycleService.recordSuccess(jobId, owner, context.userBookId(), result);
    }

    private void requeueForPacing(Long jobId, String owner) {
        lifecycleService.requeue(jobId, owner, LocalDateTime.now().plusSeconds(properties.pacedRetrySeconds()));
    }

    /** burst 429 에 Retry-After 가 실려 있으면 그 시각을 재시도 시점으로 쓴다(없으면 서비스의 지수 백오프). */
    private LocalDateTime retryAtFrom(RateLimitInfo info) {
        if (info != null && info.retryAfter() != null) {
            return LocalDateTime.now().plus(info.retryAfter());
        }
        return null;
    }
}
