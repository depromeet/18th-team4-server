package com.readum.domain.summary.service;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.SummaryJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
class EnqueueSummaryJobServiceIntegrationTest {

    @Autowired
    private EnqueueSummaryJobService enqueueSummaryJobService;

    @Autowired
    private SummaryJobRepository summaryJobRepository;

    @Test
    void 같은_세션을_두번_적재해도_예외없이_작업은_하나만_생긴다() {
        long sessionId = 730_001L;
        try {
            enqueueSummaryJobService.execute(sessionId, SummaryJob.ExecutionMode.BATCH);
            assertThatCode(() -> enqueueSummaryJobService.execute(sessionId, SummaryJob.ExecutionMode.BATCH)).doesNotThrowAnyException();

            long activeJobCount = summaryJobRepository.findAll().stream()
                    .filter(job -> sessionId == job.getAiChatSessionId())
                    .count();
            assertThat(activeJobCount).isEqualTo(1);
        } finally {
            summaryJobRepository.findAll().stream()
                    .filter(job -> sessionId == job.getAiChatSessionId())
                    .forEach(summaryJobRepository::delete);
        }
    }
}
