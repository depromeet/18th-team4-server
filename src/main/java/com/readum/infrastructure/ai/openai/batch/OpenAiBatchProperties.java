package com.readum.infrastructure.ai.openai.batch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * OpenAI Batch API 어댑터 설정 (외부 API 시크릿·호스트·모델·완료 대기 창·요청 타임아웃).
 *
 * <p>API 키는 환경변수 {@code OPENAI_API_KEY} 로 주입한다. 코드에 직접 넣지 않는다.
 * api-key 는 환경변수로, model·base-url·completion-window·request-timeout 은
 * {@code application.yml} 기본값으로 관리한다.
 *
 * <p>설정 예시 ({@code application.yml}):
 * <pre>
 * openai-batch:
 *   api-key: ${OPENAI_API_KEY}
 *   model: gpt-4o-mini
 *   base-url: https://api.openai.com/v1
 *   completion-window: 24h
 *   request-timeout: PT60S
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "openai-batch")
public record OpenAiBatchProperties(
        /** OpenAI API 인증 키. 환경변수 {@code OPENAI_API_KEY} 로 주입. */
        @NotBlank String apiKey,

        /** Batch 요청에 사용할 모델 식별자 (예: {@code gpt-4o-mini}). */
        @NotBlank String model,

        /** OpenAI API 기본 URL. 기본값 {@code https://api.openai.com/v1}. */
        @NotBlank String baseUrl,

        /**
         * OpenAI Batch API 완료 대기 창 (completion_window).
         * 허용 값: {@code 24h}. 현재 OpenAI 가 {@code 24h} 만 지원한다.
         */
        @NotBlank String completionWindow,

        /**
         * RestClient 연결·읽기 타임아웃. 파일 업로드·다운로드가 포함되므로 넉넉하게 설정한다.
         * 기본값 {@code PT60S}(60초). ISO-8601 Duration 형식.
         */
        @NotNull Duration requestTimeout
) {
}
