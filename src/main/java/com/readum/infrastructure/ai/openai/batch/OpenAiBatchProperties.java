package com.readum.infrastructure.ai.openai.batch;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OpenAI Batch API 어댑터 설정 (외부 API 시크릿·호스트·모델·완료 대기 창).
 *
 * <p>API 키는 환경변수 {@code OPENAI_API_KEY} 로 주입한다. 코드에 직접 넣지 않는다.
 * 기본값이 있는 항목({@code baseUrl}, {@code completionWindow})은 환경마다 다르지 않으므로
 * {@code application.yml} 에 직접 값을 둔다.
 *
 * <p>설정 예시 ({@code application-dev.yml}):
 * <pre>
 * openai-batch:
 *   api-key: ${OPENAI_API_KEY}
 *   model: gpt-4o-mini
 *   base-url: https://api.openai.com/v1
 *   completion-window: 24h
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
        @NotBlank String completionWindow
) {
}
