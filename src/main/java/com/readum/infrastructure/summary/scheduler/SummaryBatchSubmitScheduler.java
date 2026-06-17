package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.config.SummaryBatchProperties;
import com.readum.domain.summary.service.SummaryBatchSubmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주기적으로 batch builder 작업을 풀에 제출한다. 실제 선점은 submitService 가 SKIP LOCKED 로 하므로
 * 여러 builder 스레드가 동시에 돌아도 같은 청크를 겹쳐 잡지 않는다.
 * 동시에 도는 builder 수를 builderConcurrency 로 제한해 동시 제출량을 한도 아래로 묶는다.
 */
@Slf4j
@Component
public class SummaryBatchSubmitScheduler {

    private final SummaryBatchSubmitService submitService;
    private final Executor summaryBatchExecutor;
    private final SummaryBatchProperties batchProperties;
    private final AtomicInteger running = new AtomicInteger(0);

    public SummaryBatchSubmitScheduler(
            SummaryBatchSubmitService submitService,
            @Qualifier("summaryBatchExecutor") Executor summaryBatchExecutor,
            SummaryBatchProperties batchProperties
    ) {
        this.submitService = submitService;
        this.summaryBatchExecutor = summaryBatchExecutor;
        this.batchProperties = batchProperties;
    }

    @Scheduled(fixedDelayString = "${summary-batch.submit-interval-ms}")
    public void dispatch() {
        int slots = batchProperties.builderConcurrency() - running.get();
        for (int i = 0; i < slots; i++) {
            running.incrementAndGet();
            summaryBatchExecutor.execute(() -> {
                try {
                    while (submitService.submitOneChunk()) {
                        // 처리할 청크가 있는 동안 계속 제출한다.
                    }
                } catch (Exception e) {
                    log.error("감상문 batch builder 오류", e);
                } finally {
                    running.decrementAndGet();
                }
            });
        }
    }
}
