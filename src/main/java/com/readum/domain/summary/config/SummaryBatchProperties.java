package com.readum.domain.summary.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Batch builder·collector 의 비즈니스 룰(환경 무관 상수). 잘못된 값(0/음수)은 부팅 시 차단(@Validated).
 */
@Validated
@ConfigurationProperties(prefix = "summary-batch")
public record SummaryBatchProperties(
        /** 동시에 실행할 builder 수. */
        @Positive int builderConcurrency,
        /** 청크 하나당 허용 토큰 상한. 이 값을 넘으면 해당 작업부터 다음 청크로 넘긴다. */
        @Positive long chunkTokenLimit,
        /** 청크 하나당 작업 수 안전 상한. 토큰 예산보다 먼저 닿으면 건수로 끊는다. */
        @Positive int maxJobsPerBatch,
        /** builder 디스패치 주기(ms). */
        @Positive long submitIntervalMs,
        /** collector 폴링 주기(ms). */
        @Positive long collectIntervalMs,
        /** BATCH_BUILDING 점유 시한(초). 업로드+제출 최악 소요를 넉넉히 초과하는 값으로 설정한다. */
        @Positive int buildLeaseSeconds
) {

    /** BATCH_BUILDING 점유 시한을 Duration 으로 반환한다. */
    public Duration buildLease() {
        return Duration.ofSeconds(buildLeaseSeconds);
    }
}
