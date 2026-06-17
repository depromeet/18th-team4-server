package com.readum.domain.summary.service;

import com.readum.domain.summary.config.SummaryBatchProperties;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryBatchBuildItem;
import com.readum.domain.summary.dto.SummaryBatchRequestItem;
import com.readum.domain.summary.out.SummaryBatchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryBatchSubmitServiceTest {

    @Mock private SummaryJobLifecycleService lifecycleService;
    @Mock private SummaryTokenEstimator tokenEstimator;
    @Mock private SummaryBatchClient batchClient;
    @Mock private SummaryJobProperties jobProperties;
    @Mock private SummaryBatchProperties batchProperties;

    @InjectMocks private SummaryBatchSubmitService submitService;

    // 테스트용 빌드 아이템 — 메시지는 빈 리스트로 충분하다(토큰 추정은 mock 으로 제어)
    private static SummaryBatchBuildItem buildItem(long jobId) {
        return new SummaryBatchBuildItem(jobId, 100L + jobId, 200L + jobId, List.of());
    }

    @Test
    void 토큰_상한에_닿으면_첫_항목만_청크에_포함하고_나머지는_반환한다() {
        // 준비: chunkTokenLimit=3000, 각 작업 추정 토큰=2000 → 두 번째 작업에서 2000+2000=4000>3000 → 1개만 제출
        given(batchProperties.maxJobsPerBatch()).willReturn(100);
        given(batchProperties.buildLease()).willReturn(Duration.ofMinutes(5));
        given(batchProperties.chunkTokenLimit()).willReturn(3000L);
        given(jobProperties.reservedOutputTokens()).willReturn(1000);

        SummaryBatchBuildItem item1 = buildItem(1L);
        SummaryBatchBuildItem item2 = buildItem(2L);
        given(lifecycleService.claimBatchChunk(anyString(), eq(100), any(Duration.class)))
                .willReturn(List.of(item1, item2));
        // 두 항목 모두 2000토큰으로 추정
        given(tokenEstimator.estimate(any(), eq(1000))).willReturn(2000);

        given(batchClient.submit(any())).willReturn(new SummaryBatchClient.BatchSubmission("batch_AAA", "file_in"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SummaryBatchRequestItem>> requestCaptor = ArgumentCaptor.forClass(List.class);
        submitService.submitOneChunk();

        // 제출 호출 검증: 1개만 들어간다
        verify(batchClient).submit(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).hasSize(1);
        assertThat(requestCaptor.getValue().get(0).customId()).isEqualTo("summaryjob-1");

        // 초과분(item2) 은 즉시 반환
        verify(lifecycleService).releaseBuilding(eq(List.of(2L)), anyString());
    }

    @Test
    void maxJobsPerBatch_건수에_닿으면_건수로_청크를_끊는다() {
        // 준비: 선점된 2개 모두 토큰 상한 이내 → 전부 제출, 초과분 없음
        given(batchProperties.maxJobsPerBatch()).willReturn(2);
        given(batchProperties.buildLease()).willReturn(Duration.ofMinutes(5));
        given(batchProperties.chunkTokenLimit()).willReturn(1_000_000L);
        given(jobProperties.reservedOutputTokens()).willReturn(1000);

        List<SummaryBatchBuildItem> claimedItems = List.of(
                buildItem(1L), buildItem(2L));
        given(lifecycleService.claimBatchChunk(anyString(), eq(2), any(Duration.class)))
                .willReturn(claimedItems);
        given(tokenEstimator.estimate(any(), eq(1000))).willReturn(100);
        given(batchClient.submit(any())).willReturn(new SummaryBatchClient.BatchSubmission("batch_BBB", "file_in"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SummaryBatchRequestItem>> requestCaptor = ArgumentCaptor.forClass(List.class);
        submitService.submitOneChunk();

        verify(batchClient).submit(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).hasSize(2);

        // 초과분 없음 — releaseBuilding 은 호출되지 않는다
        verify(lifecycleService, never()).releaseBuilding(any(), anyString());
    }

    @Test
    void 제출_성공하면_recordSubmission을_jobId_목록과_batchId로_호출한다() {
        // 준비: 작업 2개, 토큰 합산 상한 이내 → 전부 제출
        given(batchProperties.maxJobsPerBatch()).willReturn(10);
        given(batchProperties.buildLease()).willReturn(Duration.ofMinutes(5));
        given(batchProperties.chunkTokenLimit()).willReturn(100_000L);
        given(jobProperties.reservedOutputTokens()).willReturn(1000);

        SummaryBatchBuildItem item1 = buildItem(10L);
        SummaryBatchBuildItem item2 = buildItem(20L);
        given(lifecycleService.claimBatchChunk(anyString(), eq(10), any(Duration.class)))
                .willReturn(List.of(item1, item2));
        given(tokenEstimator.estimate(any(), eq(1000))).willReturn(500);
        given(batchClient.submit(any()))
                .willReturn(new SummaryBatchClient.BatchSubmission("batch_CCC", "file_in_xyz"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> jobIdsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> batchIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> inputFileIdCaptor = ArgumentCaptor.forClass(String.class);

        submitService.submitOneChunk();

        verify(lifecycleService).recordSubmission(
                jobIdsCaptor.capture(),
                ownerCaptor.capture(),
                batchIdCaptor.capture(),
                inputFileIdCaptor.capture()
        );
        assertThat(jobIdsCaptor.getValue()).containsExactly(10L, 20L);
        assertThat(batchIdCaptor.getValue()).isEqualTo("batch_CCC");
        assertThat(inputFileIdCaptor.getValue()).isEqualTo("file_in_xyz");
    }

    @Test
    void 선점된_작업이_없으면_false를_반환한다() {
        given(batchProperties.maxJobsPerBatch()).willReturn(10);
        given(batchProperties.buildLease()).willReturn(Duration.ofMinutes(5));
        given(lifecycleService.claimBatchChunk(anyString(), eq(10), any(Duration.class)))
                .willReturn(List.of());

        boolean processed = submitService.submitOneChunk();

        assertThat(processed).isFalse();
        verify(batchClient, never()).submit(any());
    }

    @Test
    void 제출_예외시_점유_반환하고_true를_반환한다() {
        // 작업 2개로 설정 — 두 번째 항목 처리 시 chunkTokenLimit 비교가 실제로 실행된다.
        given(batchProperties.maxJobsPerBatch()).willReturn(10);
        given(batchProperties.buildLease()).willReturn(Duration.ofMinutes(5));
        given(batchProperties.chunkTokenLimit()).willReturn(100_000L);
        given(jobProperties.reservedOutputTokens()).willReturn(1000);

        SummaryBatchBuildItem item1 = buildItem(30L);
        SummaryBatchBuildItem item2 = buildItem(31L);
        given(lifecycleService.claimBatchChunk(anyString(), eq(10), any(Duration.class)))
                .willReturn(List.of(item1, item2));
        given(tokenEstimator.estimate(any(), eq(1000))).willReturn(500);
        given(batchClient.submit(any())).willThrow(new RuntimeException("OpenAI 연결 오류"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> jobIdsCaptor = ArgumentCaptor.forClass(List.class);

        boolean processed = submitService.submitOneChunk();

        assertThat(processed).isTrue();
        verify(lifecycleService).releaseBuilding(jobIdsCaptor.capture(), anyString());
        assertThat(jobIdsCaptor.getValue()).containsExactlyInAnyOrder(30L, 31L);
        verify(lifecycleService, never()).recordSubmission(any(), anyString(), anyString(), anyString());
    }
}
