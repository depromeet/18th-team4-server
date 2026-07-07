package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.dto.ContextSummaryGenerationContext;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiContextSummaryClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.out.AiQuotaCooldown;
import com.readum.model.aiChat.entity.AiChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 동기 컨텍스트 요약 워커. 트랜잭션 없이 각 트랜잭션 단계(ContextSummaryJobLifecycleService)를 순서대로 호출하고
 * 그 사이(트랜잭션 밖)에서 LLM 을 부른다. 분당 예산·quota 쿨다운은 전역 게이트가 AiContextSummaryClientImpl 안에서 검사한다.
 * 워커는 쿨다운 중엔 job 을 선점하지 않아(헛선점·attempt 소진 방지), 계정 전역 백오프가 전 경로 공통이다.
 * 감상문 워커(SummaryGenerationWorker)와 같은 골격 — 공통 추상화로 묶지 않는다(두 큐의 생명주기가 다름).
 *
 * 실패 분류:
 * - 추정 토큰 > maxRequestTokens → 호출 전 즉시 FAILED(fail-fast). 채팅은 최근 원문 최대 토큰 안에서 원문으로 계속 동작.
 * - burst 429(게이트 분당 예산 포화) → 무벌점 반납
 * - quota 429(게이트 쿨다운) → 재시도 가능 실패로 기록. 쿨다운은 게이트가 관리
 * - 5xx → 재시도 / 4xx → 즉시 FAILED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextSummaryWorker {

    private static final String ERROR_SESSION_TOO_LARGE = "CONTEXT_SUMMARY_SESSION_TOO_LARGE";

    private final ContextSummaryJobLifecycleService lifecycleService;
    private final AiContextSummaryClient aiContextSummaryClient;
    private final AiQuotaCooldown quotaCooldown;
    private final TokenCounter tokenCounter;
    private final ContextSummaryJobProperties jobProperties;
    private final AiChatProperties aiChatProperties;

    /** 처리할 작업이 없을 때까지(또는 전역 차단 전까지) 계속 선점·처리한다. */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** 작업 하나를 시도. 처리했으면 true, 없거나 차단 중이면 false. */
    public boolean processOne() {
        if (quotaCooldown.isCoolingDown()) {
            // 계정 quota 쿨다운 중 — job 을 선점하지 않는다(헛선점·attempt 소진 방지). 다음 dispatch 때 재확인.
            return false;
        }
        String owner = UUID.randomUUID().toString();
        Long jobId = lifecycleService.claimOne(owner);
        if (jobId == null) {
            return false;
        }
        runJob(jobId, owner);
        return !Thread.currentThread().isInterrupted();
    }

    private void runJob(Long jobId, String owner) {
        ContextSummaryGenerationContext context = lifecycleService.prepareGeneration(jobId, owner);
        if (context == null) {
            // 요약할 구간 없음(경계 진전 없음) — 준비 단계에서 이미 성공 종료됨.
            return;
        }

        int estimatedTokens = estimateTokens(context);
        if (estimatedTokens > jobProperties.maxRequestTokens()) {
            // 상한보다 큰 요청은 게이트에서도 계속 막힌다 → 즉시 실패로 드러낸다. 채팅은 최근 원문 최대 토큰 안에서 계속 동작.
            lifecycleService.recordFailure(jobId, owner, false, ERROR_SESSION_TOO_LARGE,
                    "요약 입력이 너무 큽니다 (추정 토큰 " + estimatedTokens
                            + " > 상한 " + jobProperties.maxRequestTokens() + ")", null);
            return;
        }

        ContextSummaryResult result;
        try {
            result = aiContextSummaryClient.generate(context.previousSummaryContent(), context.messagesToSummarize());
        } catch (TooManyRequestsException e) {
            handleRateLimited(jobId, owner, e);
            return;
        } catch (NonTransientAiException e) {
            log.warn("컨텍스트 요약 회복 불가 오류 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, false,
                    AiChatErrorCode.AI_PROVIDER_ERROR.name(), e.getMessage(), null);
            return;
        } catch (TransientAiException e) {
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        } catch (Exception e) {
            log.warn("컨텍스트 요약 실패 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        }
        lifecycleService.recordSuccess(jobId, owner, context, result);
    }

    private int estimateTokens(ContextSummaryGenerationContext context) {
        int total = tokenCounter.count(context.previousSummaryContent());
        for (AiChatMessage message : context.messagesToSummarize()) {
            total += tokenCounter.count(message.getContent());
        }
        return total + aiChatProperties.context().summaryEstimatedOutputTokens();
    }

    private void handleRateLimited(Long jobId, String owner, TooManyRequestsException e) {
        if (e.getErrorCode() == AiChatErrorCode.AI_QUOTA_EXHAUSTED) {
            // 계정 quota 소진 — 쿨다운은 게이트가 이미 열었다(전 경로 공통). 재시도 가능 실패로 기록.
            lifecycleService.recordFailure(jobId, owner, true,
                    e.getErrorCode().name(), e.getMessage(), retryAtFrom(e.getRateLimitInfo()));
            return;
        }
        // burst — 게이트 분당 예산 포화(backpressure). 무벌점 반납(가짜 실패 방지).
        lifecycleService.releaseWithoutPenalty(jobId, owner);
    }

    private LocalDateTime retryAtFrom(RateLimitInfo info) {
        if (info != null && info.retryAfter() != null) {
            return LocalDateTime.now().plus(info.retryAfter());
        }
        return null;
    }
}
