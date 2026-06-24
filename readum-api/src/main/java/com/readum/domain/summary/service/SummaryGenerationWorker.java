package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.domain.summary.exception.SummaryErrorCode;
import com.readum.domain.summary.out.SummaryCallBreaker;
import com.readum.domain.summary.out.SummaryCallRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 동기 감상문 생성 워커. 트랜잭션 없이, 각 트랜잭션 단계(SummaryJobLifecycleService)를 순서대로 호출하고
 * 그 사이(트랜잭션 밖)에서 외부 AI(OpenAI)를 부른다. 호출 전 페이서로 RPM/TPM 예산을 맞추고,
 * 계정 전역 문제(quota)면 브레이커로 전 워커를 잠시 멈춘다.
 *
 * 실패 분류:
 * - 세션 과대(추정 토큰 > 양동이 용량) → 호출 전 즉시 FAILED (fail-fast)
 * - 예산 미확보(페이서 false) → 무벌점 반납(시도 횟수 미증가)
 * - burst 429 → 브레이커 잠깐 차단 + 무벌점 반납(우리 과속이지 작업 잘못 아님)
 * - quota 429 → 브레이커 300초 차단 + 재시도(상한 도달 시 FAILED)
 * - 5xx → 재시도 / 4xx → 즉시 FAILED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerationWorker {

    /** quota 소진은 금방 안 풀리니 전 워커를 5분 멈춰 헛호출을 줄인다. */
    private static final Duration QUOTA_BLOCK = Duration.ofSeconds(300);
    /** burst 429 에 Retry-After 가 없을 때 적용할 기본 차단 시간. */
    private static final Duration BURST_BLOCK_DEFAULT = Duration.ofSeconds(5);

    private final SummaryJobLifecycleService lifecycleService;
    private final AiSummaryClient aiSummaryClient;
    private final SummaryCallRateLimiter rateLimiter;
    private final SummaryCallBreaker breaker;
    private final SummaryTokenEstimator tokenEstimator;
    private final SummaryJobProperties properties;

    /** 처리할 SYNC 작업이 없을 때까지(또는 전역 차단 전까지) 계속 선점·처리한다. */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** SYNC 작업 하나를 시도. 처리했으면 true, 없거나 차단 중이면 false. */
    public boolean processOne() {
        if (breaker.isBlocked()) {
            // 계정 전역 차단 중 — 이번 사이클은 아무것도 하지 않는다. 다음 dispatch 때 재확인.
            return false;
        }
        String owner = UUID.randomUUID().toString();
        Long jobId = lifecycleService.claimOne(owner);
        if (jobId == null) {
            return false;
        }
        runJob(jobId, owner);
        // 처리 중 인터럽트(예: 셧다운)가 걸렸으면 드레인 루프를 멈춘다.
        return !Thread.currentThread().isInterrupted();
    }

    private void runJob(Long jobId, String owner) {
        SummaryGenerationContext context = lifecycleService.prepareGeneration(jobId, owner);
        if (context == null) {
            return;
        }

        int estimatedTokens = tokenEstimator.estimate(context.messages(), properties.reservedOutputTokens());
        if (estimatedTokens > rateLimiter.maxRequestTokens()) {
            // 양동이보다 큰 요청은 영원히 예산을 못 얻는다(모델 한도에도 가까움) → 즉시 실패로 드러낸다.
            // 최종 실패 로그는 recordFailure(retryable=false) 의 ERROR 한 곳으로 일원화한다(중복 방지).
            lifecycleService.recordFailure(jobId, owner, false,
                    SummaryErrorCode.SESSION_TOO_LARGE.name(),
                    "세션이 너무 커 감상문을 생성할 수 없습니다 (추정 토큰 " + estimatedTokens
                            + " > 상한 " + rateLimiter.maxRequestTokens() + ")",
                    null);
            return;
        }

        if (!acquireBudget(estimatedTokens)) {
            // 예산 미확보(포화) — 실패가 아니라 backpressure. 시도 횟수 미증가로 반납.
            lifecycleService.releaseWithoutPenalty(jobId, owner);
            return;
        }

        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(context.messages());
        } catch (TooManyRequestsException e) {
            handleRateLimited(jobId, owner, e);
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

    private boolean acquireBudget(int estimatedTokens) {
        try {
            return rateLimiter.tryAcquire(estimatedTokens);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void handleRateLimited(Long jobId, String owner, TooManyRequestsException e) {
        if (e.getErrorCode() == AiChatErrorCode.AI_QUOTA_EXHAUSTED) {
            // quota 소진 — 계정 전역 문제. 전 워커 차단 후 재시도(상한 도달 시 FAILED).
            breaker.blockFor(QUOTA_BLOCK);
            lifecycleService.recordFailure(jobId, owner, true,
                    e.getErrorCode().name(), e.getMessage(), retryAtFrom(e.getRateLimitInfo()));
            return;
        }
        // burst — 우리 과속. 전 워커 잠깐 정지 + 무벌점 반납(가짜 실패 방지).
        breaker.blockFor(burstBlock(e.getRateLimitInfo()));
        lifecycleService.releaseWithoutPenalty(jobId, owner);
    }

    /** burst 차단 시간: Retry-After 가 있으면 그만큼, 없으면 기본값. */
    private Duration burstBlock(RateLimitInfo info) {
        if (info != null && info.retryAfter() != null) {
            return info.retryAfter();
        }
        return BURST_BLOCK_DEFAULT;
    }

    /** quota 재시도 시점: Retry-After 가 실려 있으면 그 시각(없으면 서비스의 지수 백오프). */
    private LocalDateTime retryAtFrom(RateLimitInfo info) {
        if (info != null && info.retryAfter() != null) {
            return LocalDateTime.now().plus(info.retryAfter());
        }
        return null;
    }
}
