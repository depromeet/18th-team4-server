package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.model.summary.repository.SummaryJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 매일 오전 6시, 최근 대화한 세션의 감상문 생성 "작업을 적재" 한다(직접 생성하지 않음).
 * 대상 = ACTIVE + 누적 토큰 ≥ 임계값 + 마지막 채팅이 24시간 이내. (종료된 세션은 ACTIVE 가 아니라 자동 제외)
 * 실제 생성은 작업 큐 워커가 OpenAI 한도에 맞춰 분산 처리한다.
 *
 * <p>적재는 세션마다 도는 per-row 루프가 아니라 집합 단위 단일 INSERT 다 — 6시 순간 DB 부하와
 * 멀티 인스턴스 중복 스캔을 줄인다. 중복/경합 안전성은 {@code NOT EXISTS} + active_session_id
 * unique 제약 + {@code INSERT IGNORE} 가 보장한다(자세한 내용은 repository 메서드 주석).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryScheduler {

    private final SummaryJobRepository summaryJobRepository;

    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    public void enqueueDailySummaryJobs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(24);
        int enqueued = summaryJobRepository.enqueuePendingForEligibleSessions(
                SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS, since, now);
        log.info("감상문 자동 생성 작업 적재 완료 {}건", enqueued);
    }
}
