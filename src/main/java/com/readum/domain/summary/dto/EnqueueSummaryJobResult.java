package com.readum.domain.summary.dto;

/** 적재 결과 — enqueued=true 면 새 작업을 적재, false 면 이미 활성 작업이 있어 건너뜀(멱등/경합 포함). */
public record EnqueueSummaryJobResult(boolean enqueued) {
}
