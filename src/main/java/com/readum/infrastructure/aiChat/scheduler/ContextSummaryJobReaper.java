package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.service.ContextSummaryJobLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멈춘(고아) 컨텍스트 요약 작업 회수기. lease 만료된 PROCESSING 작업을 PENDING 으로 되돌려 다시 처리되게 한다.
 * 서버 재시작/워커 장애 시 작업이 영영 점유 상태에 갇히는 것을 막는다. 감상문 SummaryJobReaper 와 같은 골격.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextSummaryJobReaper {

    private static final int BATCH_SIZE = 100;

    private final ContextSummaryJobLifecycleService lifecycleService;

    @Scheduled(fixedDelayString = "${context-summary-job.reaper-interval-ms}")
    public void reclaim() {
        int reclaimed = lifecycleService.reclaimOrphans(BATCH_SIZE);
        if (reclaimed > 0) {
            log.warn("멈춘 컨텍스트 요약 작업 회수 {}건", reclaimed);
        }
    }
}
