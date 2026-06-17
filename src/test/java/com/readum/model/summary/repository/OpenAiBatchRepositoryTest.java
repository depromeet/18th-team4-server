package com.readum.model.summary.repository;

import com.readum.model.summary.entity.OpenAiBatch;
import com.readum.model.summary.entity.OpenAiBatchFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OpenAiBatchRepositoryTest {

    @Autowired
    private OpenAiBatchRepository openAiBatchRepository;

    @Test
    void findByStatus_는_SUBMITTED_배치만_반환한다() {
        OpenAiBatch submitted = openAiBatchRepository.save(
                OpenAiBatchFixture.submitted(null, "batch_sub_1", 2));

        OpenAiBatch completedBatch = OpenAiBatchFixture.submitted(null, "batch_cmp_1", 3);
        completedBatch.markCompleted("file_out_1", null);
        openAiBatchRepository.save(completedBatch);

        List<OpenAiBatch> result = openAiBatchRepository.findByStatus(OpenAiBatch.Status.SUBMITTED);

        assertThat(result).extracting(OpenAiBatch::getId).contains(submitted.getId());
        assertThat(result).allMatch(batch -> batch.getStatus() == OpenAiBatch.Status.SUBMITTED);
    }

    @Test
    void findByStatus_는_일치하는_상태가_없으면_빈_목록을_반환한다() {
        openAiBatchRepository.save(OpenAiBatchFixture.submitted(null, "batch_sub_2", 1));

        List<OpenAiBatch> result = openAiBatchRepository.findByStatus(OpenAiBatch.Status.COMPLETED);

        assertThat(result).isEmpty();
    }
}
