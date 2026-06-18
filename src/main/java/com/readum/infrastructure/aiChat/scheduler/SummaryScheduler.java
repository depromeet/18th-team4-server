package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.domain.summary.service.EnqueueSummaryJobService;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 매일 오전 6시, 최근 대화한 세션의 감상문 생성 "작업을 적재" 한다(직접 생성하지 않음).
 * 대상 = ACTIVE + 누적 토큰 ≥ 임계값 + 마지막 채팅이 24시간 이내. (종료된 세션은 ACTIVE 가 아니라 자동 제외)
 * 실제 생성은 작업 큐 워커가 OpenAI 한도에 맞춰 분산 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryScheduler {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final EnqueueSummaryJobService enqueueSummaryJobService;

    @Scheduled(cron = "0 0 6 * * *")
    public void enqueueDailySummaryJobs() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Long> targetSessionIds = aiChatSessionRepository.findAutoSummaryTargetSessionIds(
                AiChatSession.Status.ACTIVE,
                SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS,
                AiChatMessage.Status.COMPLETED,
                since);
        log.info("감상문 자동 생성 작업 적재 시작 대상 {}건", targetSessionIds.size());

        int enqueued = 0;
        for (Long sessionId : targetSessionIds) {
            try {
                if (enqueueSummaryJobService.execute(sessionId).enqueued()) {
                    enqueued++;
                }
            } catch (Exception e) {
                // 한 세션 적재 실패가 나머지 배치를 막지 않도록 격리
                log.error("감상문 작업 적재 실패 sessionId={}", sessionId, e);
            }
        }
        log.info("감상문 자동 생성 작업 적재 완료 {}건", enqueued);
    }
}
