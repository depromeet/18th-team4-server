package com.readum.domain.summary.service;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.dto.SummaryGenerationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 한 워커 스레드의 작업 처리 루프. 트랜잭션 없이, 각 트랜잭션 단계(SummaryJobLifecycleService)를
 * 순서대로 호출하고 그 사이(트랜잭션 밖)에서 OpenAI 를 부른다.
 * Plan 1 실패 분류는 단순 2분류: TooManyRequests/일반예외 모두 "재시도 가능" 으로 기록(작업 유실 0).
 * (4xx 비재시도 즉시 실패 + quota breaker + Retry-After 는 Plan 2.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryGenerationWorker {

    private final SummaryJobLifecycleService lifecycleService;
    private final AiSummaryClient aiSummaryClient;

    /** 처리할 작업이 없을 때까지 계속 선점·처리한다(처리). */
    public void processUntilEmpty() {
        while (processOne()) {
            // 처리할 게 있는 동안 계속
        }
    }

    /** 한 작업만 시도. 처리했으면 true, 큐가 비었으면 false. */
    public boolean processOne() {
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
        SummaryDraftResult result;
        try {
            result = aiSummaryClient.generate(context.messages());
        } catch (TooManyRequestsException e) {
            lifecycleService.recordFailure(jobId, owner, true, e.getErrorCode().name(), e.getMessage(), null);
            return;
        } catch (Exception e) {
            log.warn("감상문 생성 실패 jobId={} sessionId={}", jobId, context.sessionId(), e);
            lifecycleService.recordFailure(jobId, owner, true,
                    AiChatErrorCode.AI_PROVIDER_TRANSIENT.name(), e.getMessage(), null);
            return;
        }
        lifecycleService.recordSuccess(jobId, owner, context.userBookId(), result);
    }
}
