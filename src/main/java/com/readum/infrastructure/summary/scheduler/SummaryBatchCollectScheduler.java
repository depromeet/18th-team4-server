package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.service.SummaryBatchCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 제출된 OpenAI batch 결과를 수집한다.
 * 단일 실행 보장: 풀에 넘기지 않고 스케줄러 스레드에서 직접 실행한다.
 * (다중 collector·SKIP LOCKED 는 후속 PR #86 에서 도입)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryBatchCollectScheduler {

    private final SummaryBatchCollectService collectService;

    @Scheduled(fixedDelayString = "${summary-batch.collect-interval-ms}")
    public void collect() {
        try {
            collectService.collect();
        } catch (Exception e) {
            log.error("감상문 batch collector 오류", e);
        }
    }
}
