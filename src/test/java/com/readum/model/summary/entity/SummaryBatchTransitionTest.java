package com.readum.model.summary.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryBatchTransitionTest {

    @Test
    void 제출_상태로_생성된다() {
        SummaryBatch batch = SummaryBatch.createSubmitted("batch_AAA", "file_in", 3);
        assertThat(batch.getStatus()).isEqualTo(SummaryBatch.Status.SUBMITTED);
        assertThat(batch.getBatchId()).isEqualTo("batch_AAA");
        assertThat(batch.getJobCount()).isEqualTo(3);
    }

    @Test
    void 완료_기록은_결과파일을_설정한다() {
        SummaryBatch batch = SummaryBatch.createSubmitted("batch_AAA", "file_in", 3);
        batch.markCompleted("file_out", "file_err");
        assertThat(batch.getStatus()).isEqualTo(SummaryBatch.Status.COMPLETED);
        assertThat(batch.getOutputFileId()).isEqualTo("file_out");
        assertThat(batch.getErrorFileId()).isEqualTo("file_err");
    }

    @Test
    void 실패_기록은_FAILED로_바꾼다() {
        SummaryBatch batch = SummaryBatch.createSubmitted("batch_AAA", "file_in", 3);
        batch.markFailed();
        assertThat(batch.getStatus()).isEqualTo(SummaryBatch.Status.FAILED);
    }
}
