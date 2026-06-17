package com.readum.domain.summary.dto;

import com.readum.domain.aiChat.dto.SummaryDraftResult;

/**
 * 배치 제공자 결과를 customId 단위로 파싱한 항목.
 * result != null 이면 성공, failed == true 이면 실패.
 */
public record SummaryBatchResultItem(
        String customId,
        SummaryDraftResult result,
        boolean failed,
        boolean retryable,
        String errorCode,
        String errorMessage
) {

    /** 정상 생성된 감상문을 담은 성공 항목. */
    public static SummaryBatchResultItem success(String customId, SummaryDraftResult result) {
        return new SummaryBatchResultItem(customId, result, false, false, null, null);
    }

    /** 생성 실패 항목. retryable=true 면 재시도 대상, false 면 즉시 FAILED 처리. */
    public static SummaryBatchResultItem failure(
            String customId, boolean retryable, String errorCode, String errorMessage) {
        return new SummaryBatchResultItem(customId, null, true, retryable, errorCode, errorMessage);
    }
}
