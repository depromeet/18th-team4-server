package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.dto.ContextSummaryGenerationContext;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.aiChat.out.AiContextSummaryClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.domain.summary.out.ShutdownSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 동기 컨텍스트 요약 워커. 트랜잭션 없이 각 트랜잭션 단계(ContextSummaryJobLifecycleService)를 순서대로 호출하고
 * 그 사이(트랜잭션 밖)에서 LLM 을 부른다. 감상문 워커(SummaryGenerationWorker)와 같은 골격 —
 * 공통 추상화로 묶지 않는다(두 큐의 생명주기가 다름).
 *
 * <p>공급자 사정(차단·한도·결제)과 작업 사정(입력 문제·알 수 없는 오류)을 나누는 규칙도 감상문 워커와 같다.
 * 공급자 사정이면 시도 횟수를 올리지 않고 되돌리고 이번 드레인 사이클을 멈춘다.
 *
 * <p>실패 구분:
 * <ul>
 *   <li>추정 토큰 &gt; maxRequestTokens → 호출 전 즉시 실패 처리(FAILED). 채팅은 최근 원문 최대 토큰 안에서 계속 동작한다.</li>
 *   <li>공급자 차단·상태 불명 / 429 계열 → 무벌점 반납 + 이번 드레인 사이클 중단</li>
 *   <li>5xx → 시도 횟수를 쓰는 재시도 / 429 외 4xx → 즉시 FAILED</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextSummaryWorker {

    private static final String ERROR_SESSION_TOO_LARGE = "CONTEXT_SUMMARY_SESSION_TOO_LARGE";
    private static final AiAvailability.Capability CAPABILITY = AiAvailability.Capability.CONTEXT_SUMMARY;

    private final ContextSummaryJobLifecycleService lifecycleService;
    private final AiContextSummaryClient aiContextSummaryClient;
    private final AiAvailability aiAvailability;
    private final ShutdownSignal shutdownSignal;
    private final TokenCounter tokenCounter;
    private final ContextSummaryJobProperties jobProperties;
    private final AiChatProperties aiChatProperties;

    /** 처리할 작업이 없을 때까지(또는 공급자가 막힐 때까지) 계속 선점·처리한다. */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** 작업 하나를 시도. 처리했으면 true, 없거나 차단 중이면 false. */
    public boolean processOne() {
        if (shutdownSignal.isShuttingDown()) {
            // 종료 중에는 새 작업을 선점하지 않는다. 이미 진행 중인 작업은 끝까지 마친다.
            return false;
        }
        if (!aiAvailability.canProcess(CAPABILITY)) {
            return false;
        }
        String owner = UUID.randomUUID().toString();
        Long jobId = lifecycleService.claimOne(owner);
        if (jobId == null) {
            notifyDrainedIfEmpty();
            return false;
        }
        boolean continueDraining = runJob(jobId, owner);
        return continueDraining && !Thread.currentThread().isInterrupted();
    }

    /** 선점이 빈손으로 돌아왔을 때만, 정말로 남은 작업이 없는지 확인하고 신규 접수 재개를 알린다(감상문 큐와 같은 이유). */
    private void notifyDrainedIfEmpty() {
        if (!lifecycleService.hasUnfinishedJob()) {
            aiAvailability.onQueueDrained(CAPABILITY);
        }
    }

    /** @return 이번 드레인 사이클을 계속할지 여부. 공급자가 막혔으면 false 로 멈춘다(타이트 재선점 루프 방지). */
    private boolean runJob(Long jobId, String owner) {
        ContextSummaryGenerationContext context = lifecycleService.prepareGeneration(jobId, owner);
        if (context == null) {
            // 요약할 구간 없음(경계 진전 없음) — 준비 단계에서 이미 성공 종료됨.
            return true;
        }

        int estimatedTokens = estimateTokens(context);
        if (estimatedTokens > jobProperties.maxRequestTokens()) {
            // 상한보다 큰 요청은 게이트에서도 계속 막힌다 → 즉시 실패로 드러낸다. 채팅은 최근 원문 최대 토큰 안에서 계속 동작.
            lifecycleService.recordFailure(jobId, owner, false, ERROR_SESSION_TOO_LARGE,
                    "요약 입력이 너무 큽니다 (추정 토큰 " + estimatedTokens
                            + " > 상한 " + jobProperties.maxRequestTokens() + ")", null);
            return true;
        }

        ContextSummaryResult result;
        try {
            result = aiContextSummaryClient.generate(context.previousSummaryContent(), context.messagesToSummarize());
        } catch (AiDependencyUnavailableException e) {
            return releaseForProviderPause(jobId, owner, "공급자 차단");
        } catch (TooManyRequestsException e) {
            return releaseForProviderPause(jobId, owner, rateLimitReasonOf(e));
        } catch (NonTransientAiException e) {
            log.warn("컨텍스트 요약 회복 불가 오류 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, false,
                    AiChatErrorCode.AI_PROVIDER_ERROR.name(), e.getMessage(), null);
            return true;
        } catch (TransientAiException e) {
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return true;
        } catch (Exception e) {
            log.warn("컨텍스트 요약 실패 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return true;
        }
        lifecycleService.recordSuccess(jobId, owner, context, result);
        return true;
    }

    private int estimateTokens(ContextSummaryGenerationContext context) {
        int total = tokenCounter.count(context.previousSummaryContent());
        for (AiChatMessage message : context.messagesToSummarize()) {
            total += tokenCounter.count(message.getContent());
        }
        return total + aiChatProperties.context().summaryEstimatedOutputTokens();
    }

    /** 공급자 사정으로 호출을 못 보냈다 — 시도 횟수를 올리지 않고 되돌리고, 이번 드레인 사이클을 멈춘다. */
    private boolean releaseForProviderPause(Long jobId, String owner, String reason) {
        log.info("컨텍스트 요약 작업 무벌점 반납 jobId={} 사유={}", jobId, reason);
        lifecycleService.releaseWithoutPenalty(jobId, owner);
        return false;
    }

    private String rateLimitReasonOf(TooManyRequestsException e) {
        if (e.getErrorCode() == AiChatErrorCode.AI_QUOTA_EXHAUSTED) {
            return "공급자 결제·잔액 소진";
        }
        if (e.getErrorCode() == AiChatErrorCode.AI_PROVIDER_RATE_LIMITED) {
            return "공급자 한도 초과 응답";
        }
        return "우리 쪽 제한";
    }
}
