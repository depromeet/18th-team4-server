package com.readum.model.summary.repository;

import com.readum.model.summary.entity.SummaryBatch;
import com.readum.model.summary.entity.SummaryBatchFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SummaryBatchRepositoryTest {

    @Autowired
    private SummaryBatchRepository summaryBatchRepository;

    @Test
    void findByStatus_는_SUBMITTED_배치만_반환한다() {
        SummaryBatch submitted = summaryBatchRepository.save(
                SummaryBatchFixture.submitted(null, "batch_sub_1", 2));

        SummaryBatch completedBatch = SummaryBatchFixture.submitted(null, "batch_cmp_1", 3);
        completedBatch.markCompleted("file_out_1", null);
        summaryBatchRepository.save(completedBatch);

        List<SummaryBatch> result = summaryBatchRepository.findByStatus(SummaryBatch.Status.SUBMITTED);

        assertThat(result).extracting(SummaryBatch::getId).contains(submitted.getId());
        assertThat(result).allMatch(batch -> batch.getStatus() == SummaryBatch.Status.SUBMITTED);
    }

    @Test
    void findByStatus_는_일치하는_상태가_없으면_빈_목록을_반환한다() {
        summaryBatchRepository.save(SummaryBatchFixture.submitted(null, "batch_sub_2", 1));

        List<SummaryBatch> result = summaryBatchRepository.findByStatus(SummaryBatch.Status.COMPLETED);

        assertThat(result).isEmpty();
    }
}
