package com.readum.model.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * OpenAI Batch API 에 제출된 배치 요청 하나를 영속화한다.
 * 상태: SUBMITTED(제출 완료, 결과 대기) → COMPLETED(결과 수집 완료) / FAILED(실패 또는 만료).
 * 한 배치에는 여러 감상문 작업(SummaryJob)이 묶인다.
 */
@Getter
@Entity
@Table(name = "openai_batch", indexes = {
        @Index(name = "idx_openai_batch_status", columnList = "status")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class OpenAiBatch {

    public enum Status { SUBMITTED, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OpenAI 가 발급한 배치 식별자 (예: batch_abc123). */
    @Column(name = "batch_id", nullable = false, length = 100)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    /** 입력 JSONL 파일 식별자 — 업로드 시 OpenAI 가 발급. */
    @Column(name = "input_file_id", length = 100)
    private String inputFileId;

    /** 결과 JSONL 파일 식별자 — 완료 시 OpenAI 가 발급. */
    @Column(name = "output_file_id", length = 100)
    private String outputFileId;

    /** 오류 JSONL 파일 식별자 — 부분 실패 행이 있을 때 OpenAI 가 발급. */
    @Column(name = "error_file_id", length = 100)
    private String errorFileId;

    /** 이 배치에 묶인 감상문 작업 수. */
    @Column(name = "job_count", nullable = false)
    private int jobCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** OpenAI 제출 성공 직후 기록. inputFileId 는 업로드 때 받은 식별자. */
    public static OpenAiBatch createSubmitted(String batchId, String inputFileId, int jobCount) {
        LocalDateTime now = LocalDateTime.now();
        return new OpenAiBatch(null, batchId, Status.SUBMITTED, inputFileId, null, null, jobCount, now, now);
    }

    /** 결과 수집 완료 — outputFileId, errorFileId 는 null 일 수 있다. */
    public void markCompleted(String outputFileId, String errorFileId) {
        this.status = Status.COMPLETED;
        this.outputFileId = outputFileId;
        this.errorFileId = errorFileId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 배치 실패 또는 OpenAI 측 오류 — 작업들은 별도로 재큐된다. */
    public void markFailed() {
        this.status = Status.FAILED;
        this.updatedAt = LocalDateTime.now();
    }
}
