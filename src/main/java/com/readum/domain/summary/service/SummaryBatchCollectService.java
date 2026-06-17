package com.readum.domain.summary.service;

import com.readum.domain.summary.dto.SummaryBatchResultItem;
import com.readum.domain.summary.out.SummaryBatchClient;
import com.readum.model.summary.entity.SummaryBatch;
import com.readum.model.summary.repository.SummaryBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SUBMITTED 상태인 배치 목록을 조회·폴링해 결과를 수집한다.
 * 비-TX 오케스트레이터. 트랜잭션 단계는 {@link SummaryJobLifecycleService} 의 @Transactional 메서드에 위임한다.
 *
 * <p>재수집 안전성: completeBatch 는 batch 내 모든 항목이 applyBatchResult 를 통해 적용된 뒤에만 호출된다.
 * JVM 재시작이나 중간 실패로 batch 가 SUBMITTED 에 머물더라도, applyBatchResult 의 멱등성 검사가
 * 이미 SUBMITTED 가 아닌 작업을 건너뛰므로 다음 collect() 호출은 안전하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryBatchCollectService {

    private final SummaryJobLifecycleService lifecycleService;
    private final SummaryBatchClient batchClient;
    private final SummaryBatchRepository summaryBatchRepository;

    /**
     * SUBMITTED 배치를 모두 순회하며 각각 폴링 → 결과 적용 → 완료 처리한다.
     *
     * <p>배치별 예외 격리: 한 배치에서 예외가 발생해도 나머지 배치는 계속 처리된다.
     * 항목별(applyBatchResult 내부) try/catch 는 두지 않는다 — applyBatchResult 도중 예외가 나면
     * 해당 배치가 SUBMITTED 에 머물러 다음 collect() 에서 재수집되며,
     * applyBatchResult 의 멱등성 검사(SUBMITTED 가 아닌 작업 건너뜀)가 이미 처리된 항목을 안전하게 건너뛴다.
     * 항목별로 잡아 completeBatch 까지 진행하면 실패한 항목이 영영 건너뛰어지므로 per-batch 잡기만 한다.
     */
    public void collect() {
        List<SummaryBatch> submittedBatches = summaryBatchRepository.findByStatus(SummaryBatch.Status.SUBMITTED);
        for (SummaryBatch batch : submittedBatches) {
            try {
                processOneBatch(batch);
            } catch (Exception e) {
                log.error("감상문 batch 수집 실패 batchId={}", batch.getBatchId(), e);
                // 예외를 삼키고 다음 배치로 진행 — 이 배치는 SUBMITTED 에 머물러 다음 collect() 에서 재시도된다.
            }
        }
    }

    private void processOneBatch(SummaryBatch batch) {
        SummaryBatchClient.BatchStatus status = batchClient.pollStatus(batch.getBatchId());
        switch (status.state()) {
            case RUNNING -> log.info("감상문 batch 처리 중 — batchId={}", batch.getBatchId());
            case COMPLETED -> {
                List<SummaryBatchResultItem> resultItems = batchClient.fetchResults(status);
                for (SummaryBatchResultItem resultItem : resultItems) {
                    lifecycleService.applyBatchResult(batch.getId(), resultItem);
                }
                lifecycleService.completeBatch(batch.getId(), status.outputFileId(), status.errorFileId());
                log.info("감상문 batch 수집 완료 batchId={} 항목수={}", batch.getBatchId(), resultItems.size());
            }
            case FAILED -> {
                log.error("감상문 batch 실패(제공자 측) batchId={}", batch.getBatchId());
                lifecycleService.failBatch(batch.getId());
            }
        }
    }
}
