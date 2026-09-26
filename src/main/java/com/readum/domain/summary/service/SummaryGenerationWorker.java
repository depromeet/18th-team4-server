package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.domain.summary.exception.SummaryErrorCode;
import com.readum.domain.summary.out.AiQuotaCooldown;
import com.readum.domain.summary.out.ShutdownSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 동기 감상문 생성 워커. 트랜잭션 없이, 각 트랜잭션 단계(SummaryJobLifecycleService)를 순서대로 호출하고
 * 그 사이(트랜잭션 밖)에서 외부 AI(OpenAI)를 부른다. 분당 예산·quota 쿨다운은 모두 전역 게이트가
 * AiSummaryClientImpl 안에서 검사한다 — 포화 시 burst 429, quota 소진 시 쿨다운(Redis)으로 던져진다.
 * 워커는 쿨다운 중엔 job 을 선점하지 않아(헛선점·attempt 소진 방지), 계정 전역 백오프가 전 경로 공통이다.
 * 종료 중에도 같은 이유로 선점하지 않는다 — 마칠 시간이 없는 작업을 집으면 벌점을 안고 재시도되기 때문(ShutdownSignal).
 *
 * 실패 분류:
 * - 세션 과대(추정 토큰 > maxRequestTokens) → 호출 전 즉시 FAILED (fail-fast)
 * - burst 429(게이트 분당 예산 포화) → 무벌점 반납(우리 과속이지 작업 잘못 아님) + 이번 드레인 사이클 중단
 * - quota 429(게이트 쿨다운) → 재시도 가능 실패로 기록(상한 도달 시 FAILED) + 이번 드레인 사이클 중단. 쿨다운은 게이트가 관리
 * - 5xx → 재시도 / 4xx → 즉시 FAILED
 *
 * burst/quota 는 계정 전역 신호다 — 게이트는 HTTP 전에(로컬 예산 검사만으로) 429 를 던지므로, 무벌점 반납한 작업을
 * 같은 사이클에서 다시 선점하면 예산이 찰 때까지 claim→반납을 무한 반복하며 DB 를 두드린다. 그래서 quota 쿨다운
 * 조기 반환과 같은 취지로, 전역 게이트가 포화면 이번 드레인 사이클을 멈추고 다음 dispatch 주기에 다시 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerationWorker {

    private final SummaryJobLifecycleService lifecycleService;
    private final AiSummaryClient aiSummaryClient;
    private final AiQuotaCooldown quotaCooldown;
    private final ShutdownSignal shutdownSignal;
    private final SummaryTokenEstimator tokenEstimator;
    private final SummaryJobProperties properties;

    /** 처리할 SYNC 작업이 없을 때까지(또는 전역 차단·종료 전까지) 계속 선점·처리한다. */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** SYNC 작업 하나를 시도. 처리했으면 true, 없거나 차단 중이면 false. */
    public boolean processOne() {
        if (shutdownSignal.isShuttingDown()) {
            // 종료 중 — 새 작업을 집지 않는다. 지금 집으면 마칠 시간이 없어 벌점을 안고 재시도되거나
            // 점유 상태로 남는다. 이미 손에 든 작업은 이 검사를 지났으므로 끝까지 마친다.
            return false;
        }
        if (quotaCooldown.isCoolingDown()) {
            // 계정 quota 쿨다운 중 — job 을 선점하지 않는다(헛선점·attempt 소진 방지). 다음 dispatch 때 재확인.
            return false;
        }
        String owner = UUID.randomUUID().toString();
        Long jobId = lifecycleService.claimOne(owner);
        if (jobId == null) {
            return false;
        }
        boolean continueDraining = runJob(jobId, owner);
        // 처리 중 인터럽트(예: 셧다운)가 걸렸거나 전역 게이트가 포화면 드레인 루프를 멈춘다.
        return continueDraining && !Thread.currentThread().isInterrupted();
    }

    /** @return 이번 드레인 사이클을 계속할지 여부. 전역 게이트 포화(burst/quota)면 false 로 멈춘다(타이트 재선점 루프 방지). */
    private boolean runJob(Long jobId, String owner) {
        SummaryGenerationContext context = lifecycleService.prepareGeneration(jobId, owner);
        if (context == null) {
            return true;
        }

        int estimatedTokens = tokenEstimator.estimate(context.messages(), properties.estimatedOutputTokens());
        if (estimatedTokens > properties.maxRequestTokens()) {
            // 상한보다 큰 요청은 게이트에서도 계속 막힌다(모델 한도에도 가까움) → 즉시 실패로 드러낸다.
            // 최종 실패 로그는 recordFailure(retryable=false) 의 ERROR 한 곳으로 일원화한다(중복 방지).
            lifecycleService.recordFailure(jobId, owner, false,
                    SummaryErrorCode.SESSION_TOO_LARGE.name(),
                    "세션이 너무 커 감상문을 생성할 수 없습니다 (추정 토큰 " + estimatedTokens
                            + " > 상한 " + properties.maxRequestTokens() + ")",
                    null);
            return true;
        }

        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(context.messages());
        } catch (TooManyRequestsException e) {
            return handleRateLimited(jobId, owner, e);
        } catch (NonTransientAiException e) {
            // 429 외 4xx — 재시도해도 같은 실패. 즉시 종료한다.
            log.warn("감상문 생성 회복 불가 오류 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, false,
                    AiChatErrorCode.AI_PROVIDER_ERROR.name(), e.getMessage(), null);
            return true;
        } catch (TransientAiException e) {
            // 5xx — 일시 오류. 재시도한다.
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return true;
        } catch (Exception e) {
            // 알 수 없는 오류 — 보수적으로 재시도(작업 유실 방지).
            log.warn("감상문 생성 실패 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return true;
        }
        lifecycleService.recordSuccess(jobId, owner, context.userBookId(), result);
        return true;
    }

    /** @return 항상 false — 전역 게이트 포화이므로 이번 드레인 사이클을 멈춘다(다음 dispatch 주기에 재시도). */
    private boolean handleRateLimited(Long jobId, String owner, TooManyRequestsException e) {
        if (e.getErrorCode() == AiChatErrorCode.AI_QUOTA_EXHAUSTED) {
            // 계정 quota 소진 — 쿨다운은 게이트가 이미 열었다(전 경로 공통). 재시도 가능 실패로 기록.
            lifecycleService.recordFailure(jobId, owner, true,
                    e.getErrorCode().name(), e.getMessage(), retryAtFrom(e.getRateLimitInfo()));
            return false;
        }
        // burst — 게이트 분당 예산 포화(backpressure). 무벌점 반납(가짜 실패 방지).
        lifecycleService.releaseWithoutPenalty(jobId, owner);
        return false;
    }

    /** quota 재시도 시점: Retry-After 가 실려 있으면 그 시각(없으면 서비스의 지수 백오프). */
    private LocalDateTime retryAtFrom(RateLimitInfo info) {
        if (info != null && info.retryAfter() != null) {
            return LocalDateTime.now().plus(info.retryAfter());
        }
        return null;
    }
}
