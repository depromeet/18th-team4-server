package com.readum.infrastructure.ai.openai.availability;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 공급자 장애 판정·차단·복구 확인의 손잡이. 다섯 기능이 같은 값을 쓴다 — 무엇을 장애로 볼지는 기능마다 다르지 않다.
 * 상태(차단 여부·남은 시간·복구 확인 권한)만 기능별로 따로 센다.
 *
 * <p>앞의 셋은 <b>차단기 라이브러리(Resilience4j)의 손잡이를 그대로 옮긴 것</b>이다. 무엇을 장애로 셀지와
 * 언제 열지는 라이브러리가 계산하고, 우리는 값만 정한다. 예전에 있던 "연속 실패 N회" 는 <b>없앴다</b> —
 * 같은 판단을 라이브러리가 "최소 표본 + 실패율" 로 이미 하고 있어서, 그것만 남기고 우리 계산을 지웠다.
 * 연달아 다 실패하는 흐름은 최소 표본을 5로 두면 5건 중 5건 실패(100%)로 기준을 넘어 그대로 걸린다.
 *
 * @param windowSeconds               호출 결과를 함께 세는 관찰 구간 길이(시간 기준 슬라이딩 창).
 * @param minimumNumberOfCalls        창 판정에 필요한 최소 표본(호출) 수. 이 수에 닿기 전에는 비율로 판정하지 않는다.
 * @param failureRatePercent          창 안 실패 비율이 이 백분율에 닿으면 차단한다.
 * @param transientBlockSeconds       일시 실패로 차단할 때의 기본 차단 시간.
 * @param rateLimitBlockSeconds       429 한도 초과로 차단할 때의 기본 차단 시간(공급자가 Retry-After 를 주면 그 값을 쓴다).
 * @param quotaBlockSeconds           결제·지출 한도 문제로 차단할 때의 시간. 곧 풀릴 종류가 아니라 길게 둔다.
 * @param authBlockSeconds            키·권한 설정 문제로 차단할 때의 시간. 사람이 고쳐야 풀리므로 길게 둔다.
 * @param maxBlockSeconds             복구 확인이 거듭 실패해 간격을 배로 늘릴 때의 상한.
 *                                    <b>공급자가 준 Retry-After 에는 적용하지 않는다</b> — 그 시각 안에 두드리면
 *                                    똑같이 거절당하기 때문이다.
 * @param probeLeaseSeconds           복구 확인 한 건이 결과를 돌려줄 때까지 다른 확인을 막아 두는 시간.
 *                                    이 시간이 지나도 결과가 없으면 확인자가 죽은 것으로 보고 다음 회차가 넘겨받는다.
 */
@Validated
@ConfigurationProperties(prefix = "openai.availability")
public record AiAvailabilityProperties(
        @Positive long windowSeconds,
        @Positive int minimumNumberOfCalls,
        @Positive @Max(100) int failureRatePercent,
        @Positive long transientBlockSeconds,
        @Positive long rateLimitBlockSeconds,
        @Positive long quotaBlockSeconds,
        @Positive long authBlockSeconds,
        @Positive long maxBlockSeconds,
        @Positive long probeLeaseSeconds
) {

    public Duration window() {
        return Duration.ofSeconds(windowSeconds);
    }

    public Duration probeLease() {
        return Duration.ofSeconds(probeLeaseSeconds);
    }

    public Duration maxBlock() {
        return Duration.ofSeconds(maxBlockSeconds);
    }

    /** 실패 종류별 기본 차단 시간. 공급자가 Retry-After 를 주면 호출 지점이 그 값으로 갈음한다. */
    public Duration baseBlockFor(AiProviderFailureKind kind) {
        return switch (kind) {
            case RATE_LIMIT -> Duration.ofSeconds(rateLimitBlockSeconds);
            case QUOTA -> Duration.ofSeconds(quotaBlockSeconds);
            case AUTH -> Duration.ofSeconds(authBlockSeconds);
            case TRANSIENT, NOT_COUNTED -> Duration.ofSeconds(transientBlockSeconds);
        };
    }
}
