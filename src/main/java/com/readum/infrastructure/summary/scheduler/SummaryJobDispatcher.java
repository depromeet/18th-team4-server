package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.service.SummaryGenerationWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주기적으로 워커 처리 작업을 풀에 제출한다. 실제 선점은 워커가 SKIP LOCKED 로 하므로
 * 여러 처리 스레드가 동시에 돌아도 같은 작업을 겹쳐 잡지 않는다.
 * 동시에 도는 처리 수를 poolSize 로 제한해 OpenAI 동시성을 한도 아래로 묶는다.
 */
@Slf4j
@Component
public class SummaryJobDispatcher {

    private final SummaryGenerationWorker worker;
    private final Executor summaryExecutor;
    private final SummaryJobProperties properties;
    private final AtomicInteger runningWorkers = new AtomicInteger(0);

    public SummaryJobDispatcher(
            SummaryGenerationWorker worker,
            @Qualifier("summaryExecutor") Executor summaryExecutor,
            SummaryJobProperties properties
    ) {
        this.worker = worker;
        this.summaryExecutor = summaryExecutor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${summary-job.dispatch-interval-ms}")
    public void dispatch() {
        int slots = properties.poolSize() - runningWorkers.get();
        for (int i = 0; i < slots; i++) {
            runningWorkers.incrementAndGet();
            summaryExecutor.execute(() -> {
                try {
                    worker.processUntilEmpty();
                } catch (Exception e) {
                    log.error("감상문 워커 처리 중 오류", e);
                } finally {
                    runningWorkers.decrementAndGet();
                }
            });
        }
    }
}
