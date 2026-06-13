package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.service.SummaryDraftService;
import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class SummaryScheduler {

    private static final int MAX_RETRY_COUNT = 3;

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryDraftPolicy summaryDraftPolicy;
    private final SummaryDraftService summaryDraftService;
    private final Executor summaryExecutor;

    public SummaryScheduler(
            AiChatSessionRepository aiChatSessionRepository,
            AiChatMessageRepository aiChatMessageRepository,
            SummaryRepository summaryRepository,
            SummaryDraftPolicy summaryDraftPolicy,
            SummaryDraftService summaryDraftService,
            @Qualifier("summaryExecutor") Executor summaryExecutor
    ) {
        this.aiChatSessionRepository = aiChatSessionRepository;
        this.aiChatMessageRepository = aiChatMessageRepository;
        this.summaryRepository = summaryRepository;
        this.summaryDraftPolicy = summaryDraftPolicy;
        this.summaryDraftService = summaryDraftService;
        this.summaryExecutor = summaryExecutor;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void generateDailySummaries() {
        LocalDate today = LocalDate.now();
        log.info("독후감 자동 생성 스케줄러 시작 summaryDate={}", today);

        List<Long> eligibleSessionIds = findEligibleSessionIds();
        log.info("요약 대상 세션 {}건", eligibleSessionIds.size());

        List<CompletableFuture<Void>> futures = eligibleSessionIds.stream()
                .map(sessionId -> CompletableFuture.runAsync(
                        () -> executeSafely(sessionId, today), summaryExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("독후감 자동 생성 스케줄러 완료 summaryDate={}", today);
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void retryFailedSummaries() {
        List<Summary> failedSummaries = summaryRepository
                .findByStatusAndRetryCountLessThan(Summary.Status.FAILED, MAX_RETRY_COUNT);

        if (failedSummaries.isEmpty()) {
            return;
        }

        log.info("실패 독후감 재시도 {}건", failedSummaries.size());

        for (Summary summary : failedSummaries) {
            try {
                summaryDraftService.retryForScheduler(summary.getId());
            } catch (Exception e) {
                log.error("독후감 재시도 실패 summaryId={} sessionId={}",
                        summary.getId(), summary.getAiChatSessionId(), e);
            }
        }
    }

    private List<Long> findEligibleSessionIds() {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);

        return aiChatSessionRepository
                .findByStatusAndAccumulatedTokensGreaterThanEqualAndUpdatedAtAfter(
                        AiChatSession.Status.ACTIVE,
                        SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS,
                        yesterday)
                .stream()
                .filter(session -> {
                    LocalDateTime since = summaryRepository
                            .findFirstByAiChatSessionIdOrderByCreatedAtDesc(session.getId())
                            .map(Summary::getCreatedAt)
                            .orElse(session.getCreatedAt());

                    int accumulatedTokens = aiChatMessageRepository
                            .findValidMessagesSince(session.getId(), since)
                            .stream()
                            .mapToInt(message -> message.getOutputTokens() != null
                                    ? message.getOutputTokens() : 0)
                            .sum();

                    return summaryDraftPolicy.isEligible(accumulatedTokens);
                })
                .map(AiChatSession::getId)
                .toList();
    }

    private void executeSafely(Long sessionId, LocalDate summaryDate) {
        try {
            summaryDraftService.executeForScheduler(sessionId, summaryDate);
        } catch (Exception e) {
            log.error("독후감 자동 생성 실패 sessionId={}", sessionId, e);
        }
    }
}
