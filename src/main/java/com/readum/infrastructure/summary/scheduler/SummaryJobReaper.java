package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.service.SummaryJobTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멈춘(고아) 작업 회수기. lease 만료된 PROCESSING 작업을 PENDING 으로 되돌려 다시 처리되게 한다.
 * 서버 재시작/워커 장애 시 작업이 영영 PROCESSING 에 갇히는 것을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryJobReaper {

    private static final int BATCH_SIZE = 100;

    private final SummaryJobTxService summaryJobTxService;

    @Scheduled(fixedDelayString = "${summary-job.reaper-interval-ms}")
    public void reclaim() {
        int reclaimed = summaryJobTxService.reclaimOrphans(BATCH_SIZE);
        if (reclaimed > 0) {
            log.info("멈춘 감상문 작업 회수 {}건", reclaimed);
        }
    }
}
