package com.readum.domain.summary.service;

import com.readum.domain.summary.dto.SummaryBatchResultItem;
import com.readum.domain.summary.out.SummaryBatchClient;
import com.readum.model.summary.entity.OpenAiBatch;
import com.readum.model.summary.entity.OpenAiBatchFixture;
import com.readum.model.summary.repository.OpenAiBatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryBatchCollectServiceTest {

    @Mock private SummaryJobLifecycleService lifecycleService;
    @Mock private SummaryBatchClient batchClient;
    @Mock private OpenAiBatchRepository openAiBatchRepository;

    @InjectMocks private SummaryBatchCollectService collectService;

    @Test
    void COMPLETED_배치는_결과를_항목별로_적용하고_completeBatch를_호출한다() {
        OpenAiBatch batch = OpenAiBatchFixture.submitted(1L, "batch_AAA", 2);
        given(openAiBatchRepository.findByStatus(OpenAiBatch.Status.SUBMITTED))
                .willReturn(List.of(batch));

        SummaryBatchClient.BatchStatus completedStatus = new SummaryBatchClient.BatchStatus(
                "batch_AAA", SummaryBatchClient.BatchStatus.State.COMPLETED, "file_out", null);
        given(batchClient.pollStatus("batch_AAA")).willReturn(completedStatus);

        SummaryBatchResultItem item1 = SummaryBatchResultItem.success(
                "summaryjob-10", new com.readum.domain.aiChat.dto.SummaryDraftResult("제목1", "본문1"));
        SummaryBatchResultItem item2 = SummaryBatchResultItem.success(
                "summaryjob-20", new com.readum.domain.aiChat.dto.SummaryDraftResult("제목2", "본문2"));
        given(batchClient.fetchResults(completedStatus)).willReturn(List.of(item1, item2));

        collectService.collect();

        verify(lifecycleService).applyBatchResult(eq(1L), eq(item1));
        verify(lifecycleService).applyBatchResult(eq(1L), eq(item2));
        verify(lifecycleService).completeBatch(eq(1L), eq("file_out"), eq(null));
    }

    @Test
    void RUNNING_배치는_결과_적용_없이_건너뛴다() {
        OpenAiBatch batch = OpenAiBatchFixture.submitted(2L, "batch_BBB", 1);
        given(openAiBatchRepository.findByStatus(OpenAiBatch.Status.SUBMITTED))
                .willReturn(List.of(batch));

        SummaryBatchClient.BatchStatus runningStatus = new SummaryBatchClient.BatchStatus(
                "batch_BBB", SummaryBatchClient.BatchStatus.State.RUNNING, null, null);
        given(batchClient.pollStatus("batch_BBB")).willReturn(runningStatus);

        collectService.collect();

        verify(lifecycleService, never()).applyBatchResult(any(), any());
        verify(lifecycleService, never()).completeBatch(any(), any(), any());
        verify(lifecycleService, never()).failBatch(any());
    }

    @Test
    void FAILED_배치는_failBatch를_호출한다() {
        OpenAiBatch batch = OpenAiBatchFixture.submitted(3L, "batch_CCC", 1);
        given(openAiBatchRepository.findByStatus(OpenAiBatch.Status.SUBMITTED))
                .willReturn(List.of(batch));

        SummaryBatchClient.BatchStatus failedStatus = new SummaryBatchClient.BatchStatus(
                "batch_CCC", SummaryBatchClient.BatchStatus.State.FAILED, null, null);
        given(batchClient.pollStatus("batch_CCC")).willReturn(failedStatus);

        collectService.collect();

        verify(lifecycleService).failBatch(3L);
        verify(lifecycleService, never()).applyBatchResult(any(), any());
    }

    @Test
    void 첫_배치에서_예외가_발생해도_두_번째_배치는_계속_처리된다() {
        // 준비: 배치 두 개 — 첫 번째는 pollStatus 에서 예외, 두 번째는 정상 완료
        OpenAiBatch batch1 = OpenAiBatchFixture.submitted(4L, "batch_FAIL", 1);
        OpenAiBatch batch2 = OpenAiBatchFixture.submitted(5L, "batch_OK", 1);
        given(openAiBatchRepository.findByStatus(OpenAiBatch.Status.SUBMITTED))
                .willReturn(List.of(batch1, batch2));

        given(batchClient.pollStatus("batch_FAIL"))
                .willThrow(new RuntimeException("OpenAI 연결 오류"));

        SummaryBatchClient.BatchStatus okStatus = new SummaryBatchClient.BatchStatus(
                "batch_OK", SummaryBatchClient.BatchStatus.State.COMPLETED, "file_out_ok", null);
        given(batchClient.pollStatus("batch_OK")).willReturn(okStatus);

        SummaryBatchResultItem okItem = SummaryBatchResultItem.success(
                "summaryjob-30", new com.readum.domain.aiChat.dto.SummaryDraftResult("제목", "본문"));
        given(batchClient.fetchResults(okStatus)).willReturn(List.of(okItem));

        // 예외가 전파되지 않고 collect() 가 정상 완료되어야 한다
        collectService.collect();

        // 첫 번째 배치는 실패했으므로 적용 없음, 두 번째 배치는 정상 처리됨
        verify(lifecycleService).applyBatchResult(eq(5L), eq(okItem));
        verify(lifecycleService).completeBatch(eq(5L), eq("file_out_ok"), eq(null));
        // 첫 번째 배치에 대한 completeBatch 는 호출되지 않는다
        verify(lifecycleService, never()).completeBatch(eq(4L), anyString(), any());
    }
}
