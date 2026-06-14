package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.service.SummaryDraftService;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 매일 오전 6시, 최근 대화한 세션의 독후감을 자동 생성한다.
 * 대상 = ACTIVE + 누적 토큰 ≥ 임계값 + 마지막 채팅이 24시간 이내(실제 메시지 시각 기준).
 * 자동 생성도 수동과 동일하게 세션 전체 대화로 매번 다시 요약하고(증분 아님),
 * 성공 시에만 새 감상문 행을 남기며 세션을 잠갔다 푼다. 실패 시엔 행을 남기지 않으므로
 * 같은 날 재시도 스케줄러는 두지 않는다 — 복구는 다음 날 정기 실행과 사용자 수동 재생성으로 한다.
 */
@Slf4j
@Component
public class SummaryScheduler {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final SummaryDraftService summaryDraftService;
    private final Executor summaryExecutor;

    public SummaryScheduler(
            AiChatSessionRepository aiChatSessionRepository,
            SummaryDraftService summaryDraftService,
            @Qualifier("summaryExecutor") Executor summaryExecutor
    ) {
        this.aiChatSessionRepository = aiChatSessionRepository;
        this.summaryDraftService = summaryDraftService;
        this.summaryExecutor = summaryExecutor;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void generateDailySummaries() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Long> targetSessionIds = aiChatSessionRepository.findAutoSummaryTargetSessionIds(
                AiChatSession.Status.ACTIVE,
                SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS,
                AiChatMessage.Status.COMPLETED,
                since);
        log.info("독후감 자동 생성 스케줄러 시작 대상 {}건", targetSessionIds.size());

        List<CompletableFuture<Void>> futures = targetSessionIds.stream()
                .map(sessionId -> CompletableFuture.runAsync(
                        () -> executeSafely(sessionId), summaryExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("독후감 자동 생성 스케줄러 완료");
    }

    private void executeSafely(Long sessionId) {
        try {
            summaryDraftService.executeForScheduler(sessionId);
        } catch (Exception e) {
            log.error("독후감 자동 생성 실패 sessionId={}", sessionId, e);
        }
    }
}
