package com.readum.infrastructure.ai.openai.availability;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 전용 복구 확인의 손잡이. 값은 모두 후보값이다 — 최적값이라는 근거도, 부하로 확인한 결과도 없다.
 *
 * @param scanIntervalMs         기한이 된 기능이 있는지 보러 도는 주기(밀리초).
 * @param connectTimeoutSeconds   확인 호출의 연결 상한.
 * @param readTimeoutSeconds      확인 호출의 응답 읽기 상한(단발 호출).
 * @param streamTotalTimeoutSeconds 채팅 확인의 스트림 전체 상한 — 이 안에 <b>종료 신호까지</b> 와야 성공이다.
 * @param maxOutputTokens         확인 호출이 받을 출력 토큰 상한. 살아 있는지만 보면 되므로 가장 작게 둔다
 *                                — 이 값이 확인 한 건의 비용 상한이자 한도 사용량 상한이다.
 */
@Validated
@ConfigurationProperties(prefix = "openai.availability.probe")
public record AiRecoveryProbeProperties(
        @Positive long scanIntervalMs,
        @Positive long connectTimeoutSeconds,
        @Positive long readTimeoutSeconds,
        @Positive long streamTotalTimeoutSeconds,
        @Positive int maxOutputTokens
) {

    public Duration connectTimeout() {
        return Duration.ofSeconds(connectTimeoutSeconds);
    }

    public Duration readTimeout() {
        return Duration.ofSeconds(readTimeoutSeconds);
    }

    public Duration streamTotalTimeout() {
        return Duration.ofSeconds(streamTotalTimeoutSeconds);
    }
}
