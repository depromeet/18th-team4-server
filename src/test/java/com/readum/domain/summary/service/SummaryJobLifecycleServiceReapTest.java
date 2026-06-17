package com.readum.domain.summary.service;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import com.readum.model.summary.repository.SummaryBatchRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SummaryJobLifecycleServiceReapTest {

    @Mock private SummaryJobRepository summaryJobRepository;
    @Mock private com.readum.model.aiChat.repository.AiChatSessionRepository aiChatSessionRepository;
    @Mock private com.readum.model.aiChat.repository.AiChatMessageRepository aiChatMessageRepository;
    @Mock private com.readum.model.summary.repository.SummaryRepository summaryRepository;
    @Mock private SummaryBatchRepository summaryBatchRepository;
    @Mock private com.readum.domain.summary.config.SummaryJobProperties properties;

    @Test
    void reclaimOrphans_는_PROCESSING_고아를_PENDING으로_되돌린다() {
        SummaryJobLifecycleService service = new SummaryJobLifecycleService(
                summaryJobRepository, aiChatSessionRepository, aiChatMessageRepository,
                summaryRepository, summaryBatchRepository, properties);
        SummaryJob orphan = SummaryJobFixture.persistedProcessing(
                10L, 1L, "dead", LocalDateTime.now().minusMinutes(1));
        given(summaryJobRepository.findOrphaned(any(), any())).willReturn(List.of(orphan));

        int reclaimed = service.reclaimOrphans(100);

        assertThat(reclaimed).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(orphan.getLockOwner()).isNull();
    }

    @Test
    void reclaimOrphans_는_BATCH_BUILDING_고아도_PENDING으로_되돌린다() {
        SummaryJobLifecycleService service = new SummaryJobLifecycleService(
                summaryJobRepository, aiChatSessionRepository, aiChatMessageRepository,
                summaryRepository, summaryBatchRepository, properties);
        SummaryJob batchOrphan = SummaryJobFixture.persistedBatchBuilding(
                20L, 2L, "dead-builder", LocalDateTime.now().minusMinutes(1));
        given(summaryJobRepository.findOrphaned(any(), any())).willReturn(List.of(batchOrphan));

        int reclaimed = service.reclaimOrphans(100);

        assertThat(reclaimed).isEqualTo(1);
        assertThat(batchOrphan.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(batchOrphan.getLockOwner()).isNull();
    }
}
