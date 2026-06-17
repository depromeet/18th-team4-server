package com.readum.domain.summary.out;

import com.readum.domain.summary.dto.SummaryBatchRequestItem;
import com.readum.domain.summary.dto.SummaryBatchResultItem;

import java.util.List;

/**
 * 외부 배치 제공자(현재 OpenAI) 연동 Port.
 * 구현체({@code SummaryBatchClientImpl})는 infrastructure 계층에 둔다.
 */
public interface SummaryBatchClient {

    /** 입력 파일을 업로드하고 batch 를 생성한다. batchId·inputFileId 를 반환한다. */
    BatchSubmission submit(List<SummaryBatchRequestItem> items);

    /** batch 처리 상태를 조회한다. */
    BatchStatus pollStatus(String batchId);

    /** 완료된 batch 의 결과를 customId 단위로 파싱해 반환한다. */
    List<SummaryBatchResultItem> fetchResults(BatchStatus status);

    /** 제출 결과 식별자. batchId = 제공자 batch 식별자, inputFileId = 업로드된 입력 파일 식별자. */
    record BatchSubmission(String batchId, String inputFileId) {}

    /** batch 처리 상태 스냅샷. */
    record BatchStatus(String batchId, State state, String outputFileId, String errorFileId) {

        /** batch 처리 상태. */
        public enum State {
            /** 처리 진행 중. */
            RUNNING,
            /** 처리 완료 — 결과 파일 조회 가능. */
            COMPLETED,
            /** 처리 실패 — 전체 batch 오류. */
            FAILED
        }
    }
}
