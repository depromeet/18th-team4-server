package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.service.ContextSummaryWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주기적으로 컨텍스트 요약 워커 처리 작업을 풀에 제출한다. 실제 선점은 워커가 SKIP LOCKED 로 하므로
 * 여러 처리 스레드가 동시에 돌아도 같은 작업을 겹쳐 잡지 않는다. 동시 처리 수를 poolSize 로 제한해 OpenAI 동시성을 묶는다.
 * 감상문 SummaryJobDispatcher 와 같은 골격.
 */
@Slf4j
@Component
public class ContextSummaryJobDispatcher {

    private final ContextSummaryWorker worker;
    private final Executor contextSummaryExecutor;
    private final ContextSummaryJobProperties properties;
    private final AtomicInteger runningWorkers = new AtomicInteger(0);

    public ContextSummaryJobDispatcher(
            ContextSummaryWorker worker,
            @Qualifier("contextSummaryExecutor") Executor contextSummaryExecutor,
            ContextSummaryJobProperties properties
    ) {
        this.worker = worker;
        this.contextSummaryExecutor = contextSummaryExecutor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${context-summary-job.dispatch-interval-ms}")
    public void dispatch() {
        int slots = properties.poolSize() - runningWorkers.get();
        for (int i = 0; i < slots; i++) {
            runningWorkers.incrementAndGet();
            try {
                contextSummaryExecutor.execute(() -> {
                    try {
                        worker.processUntilEmpty();
                    } catch (Exception e) {
                        log.error("컨텍스트 요약 워커 처리 중 오류", e);
                    } finally {
                        runningWorkers.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                runningWorkers.decrementAndGet();
                log.warn("컨텍스트 요약 워커 제출 거부 — 풀 포화, 다음 dispatch 에서 재시도", e);
                break;
            }
        }
    }
}
