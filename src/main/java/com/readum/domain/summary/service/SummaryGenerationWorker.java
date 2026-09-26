package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import com.readum.domain.summary.exception.SummaryErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 동기 감상문 생성 워커. 트랜잭션 없이, 각 트랜잭션 단계(SummaryJobLifecycleService)를 순서대로 호출하고
 * 그 사이(트랜잭션 밖)에서 외부 AI(OpenAI)를 부른다.
 *
 * <p><b>공급자 사정과 작업 사정을 나눈다.</b> 공급자가 막혀 있거나 우리가 속도를 맞추느라 못 보낸 것은
 * 이 작업이 잘못된 것이 아니므로 <b>시도 횟수를 올리지 않고</b> 대기열에 되돌린다. 반대로 이 요청 하나가
 * 잘못됐거나(4xx) 알 수 없는 오류로 실패한 것은 작업의 시도 횟수를 쓴다. 이 구분이 없으면 공급자가 몇 시간
 * 막혀 있을 때 대기 중인 작업이 전부 재시도 상한을 소진해 버린다.
 *
 * <p>실패 구분:
 * <ul>
 *   <li>세션 과대(추정 토큰 &gt; maxRequestTokens) → 호출 전 즉시 실패 처리(FAILED)</li>
 *   <li>공급자 차단·상태 불명({@link AiDependencyUnavailableException}) → 무벌점 반납 + 이번 드레인 사이클 중단</li>
 *   <li>공급자 한도 초과·결제, 우리 게이트 포화(429 계열) → 무벌점 반납 + 이번 드레인 사이클 중단</li>
 *   <li>5xx → 시도 횟수를 쓰는 재시도 / 429 외 4xx → 즉시 FAILED</li>
 * </ul>
 *
 * <p>드레인 사이클을 멈추는 이유: 무벌점 반납한 작업은 곧바로 다시 선점 가능하므로, 같은 사이클에서 계속 돌면
 * 공급자가 풀릴 때까지 선점 → 반납을 되풀이하며 DB 만 두드린다. 다음 dispatch 주기에 다시 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerationWorker {

    private static final AiAvailability.Capability CAPABILITY = AiAvailability.Capability.SUMMARY;

    private final SummaryJobLifecycleService lifecycleService;
    private final AiSummaryClient aiSummaryClient;
    private final AiAvailability aiAvailability;
    private final SummaryTokenEstimator tokenEstimator;
    private final SummaryJobProperties properties;

    /** 처리할 작업이 없을 때까지(또는 공급자가 막힐 때까지) 계속 선점·처리한다. */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** 작업 하나를 시도. 처리했으면 true, 없거나 차단 중이면 false. */
    public boolean processOne() {
        if (!aiAvailability.canProcess(CAPABILITY)) {
            // 공급자가 막혀 있거나 상태를 확인할 수 없다 — 선점하지 않는다(헛선점·시도 횟수 소진 방지).
            return false;
        }
        String owner = UUID.randomUUID().toString();
        Long jobId = lifecycleService.claimOne(owner);
        if (jobId == null) {
            notifyDrainedIfEmpty();
            return false;
        }
        boolean continueDraining = runJob(jobId, owner);
        // 처리 중 인터럽트(예: 셧다운)가 걸렸거나 공급자가 막혔으면 드레인 루프를 멈춘다.
        return continueDraining && !Thread.currentThread().isInterrupted();
    }

    /**
     * 선점이 빈손으로 돌아왔을 때만, 정말로 남은 작업이 없는지 확인하고 신규 접수 재개를 알린다.
     *
     * <p>선점이 비었다는 것만으로 다 비웠다고 보면 안 된다 — 백오프로 시도 시각이 미래인 작업이나
     * 다른 서버가 처리 중인 작업이 있으면 선점은 비어도 적체는 남아 있다. 그때 신규 접수를 열면
     * 복구가 끝나기 전에 새 작업이 섞인다.
     */
    private void notifyDrainedIfEmpty() {
        if (!lifecycleService.hasUnfinishedJob()) {
            aiAvailability.onQueueDrained(CAPABILITY);
        }
    }

    /** @return 이번 드레인 사이클을 계속할지 여부. 공급자가 막혔으면 false 로 멈춘다(타이트 재선점 루프 방지). */
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
        } catch (AiDependencyUnavailableException e) {
            // 공급자가 막혀 있거나(차단·복구 확인 중) 상태를 확인할 수 없어 호출을 시작하지 못했다.
            // 작업 잘못이 아니므로 시도 횟수를 올리지 않는다.
            return releaseForProviderPause(jobId, owner, "공급자 차단");
        } catch (TooManyRequestsException e) {
            // 공급자 한도 초과·결제 소진, 또는 우리 게이트가 속도를 맞추려고 거절한 경우.
            // 셋 다 이 작업의 잘못이 아니다 — 차단과 재개 시점은 공급자 상태가 관리한다.
            return releaseForProviderPause(jobId, owner, rateLimitReasonOf(e));
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

    /** 공급자 사정으로 호출을 못 보냈다 — 시도 횟수를 올리지 않고 되돌리고, 이번 드레인 사이클을 멈춘다. */
    private boolean releaseForProviderPause(Long jobId, String owner, String reason) {
        log.info("감상문 작업 무벌점 반납 jobId={} 사유={}", jobId, reason);
        lifecycleService.releaseWithoutPenalty(jobId, owner);
        return false;
    }

    /** 429 계열의 출처 구분 — 공급자가 실제로 한 말인지, 우리 쪽 제한이 거절한 것인지를 로그에 남긴다. */
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
