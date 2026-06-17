package com.readum.domain.summary.service;

import com.readum.domain.summary.config.SummaryBatchProperties;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.dto.SummaryBatchBuildItem;
import com.readum.domain.summary.dto.SummaryBatchRequestItem;
import com.readum.domain.summary.out.SummaryBatchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Batch builder 오케스트레이터. 비-TX — 트랜잭션 단계는 lifecycleService 의 @Transactional 메서드를 외부 호출한다.
 * 청크 선점 → 토큰 예산으로 자르기 → 초과분 반환 → 제출 → 기록 순으로 한 청크를 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryBatchSubmitService {

    private final SummaryJobLifecycleService lifecycleService;
    private final SummaryTokenEstimator tokenEstimator;
    private final SummaryBatchClient batchClient;
    private final SummaryJobProperties jobProperties;
    private final SummaryBatchProperties batchProperties;

    /**
     * 한 청크를 선점·제출한다.
     * 처리할 BATCH PENDING 작업이 있었으면 true, 없으면 false 를 반환한다.
     */
    public boolean submitOneChunk() {
        String owner = UUID.randomUUID().toString();
        List<SummaryBatchBuildItem> claimed = lifecycleService.claimBatchChunk(
                owner, batchProperties.maxJobsPerBatch(), batchProperties.buildLease());
        if (claimed.isEmpty()) {
            return false;
        }

        // 토큰 예산으로 실제 제출 묶음을 자른다.
        // 규칙: 첫 항목은 예산을 초과하더라도 반드시 포함한다(무한 대기 방지).
        // 이후 항목은 누적 토큰이 chunkTokenLimit 을 넘기 직전까지 포함한다.
        List<SummaryBatchBuildItem> chunk = new ArrayList<>();
        List<SummaryBatchRequestItem> requestItems = new ArrayList<>();
        long tokenSum = 0;
        for (SummaryBatchBuildItem buildItem : claimed) {
            int est = tokenEstimator.estimate(buildItem.messages(), jobProperties.reservedOutputTokens());
            if (!chunk.isEmpty() && tokenSum + est > batchProperties.chunkTokenLimit()) {
                break;
            }
            tokenSum += est;
            chunk.add(buildItem);
            requestItems.add(new SummaryBatchRequestItem("summaryjob-" + buildItem.jobId(), buildItem.messages()));
        }

        // 청크에 들어가지 못한 초과분을 즉시 반환한다(점유 시한 만료를 기다리지 않고 다음 builder 가 바로 선점 가능).
        List<Long> overflowJobIds = claimed.subList(chunk.size(), claimed.size())
                .stream().map(SummaryBatchBuildItem::jobId).toList();
        if (!overflowJobIds.isEmpty()) {
            lifecycleService.releaseBuilding(overflowJobIds, owner);
        }

        List<Long> jobIds = chunk.stream().map(SummaryBatchBuildItem::jobId).toList();
        try {
            SummaryBatchClient.BatchSubmission submission = batchClient.submit(requestItems);
            lifecycleService.recordSubmission(jobIds, owner, submission.batchId(), submission.inputFileId());
            log.info("감상문 batch 제출 완료 작업수={} batchId={}", jobIds.size(), submission.batchId());
        } catch (Exception e) {
            log.error("감상문 batch 제출 실패 — 점유 반환 작업수={}", jobIds.size(), e);
            lifecycleService.releaseBuilding(jobIds, owner);
        }
        return true;
    }
}
