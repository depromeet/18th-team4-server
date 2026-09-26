package com.readum.infrastructure.ai.openai.availability;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 차단 시간이 실패 종류에 따라 갈리는지 — 값 자체가 아니라 <b>대소 관계</b>가 정책이다.
 * 결제·지출 한도와 키·권한 문제는 시간이 지나 저절로 풀리는 종류가 아니라, 일시 실패보다 길게 막아야 한다.
 */
class AiAvailabilityPropertiesTest {

    private static final AiAvailabilityProperties PROPERTIES = new AiAvailabilityProperties(
            60, 10, 50, 30, 30, 300, 300, 600, 30);

    @Test
    void 결제_지출_한도와_키_권한_문제는_일시_실패보다_길게_막는다() {
        Duration transientBlock = PROPERTIES.baseBlockFor(AiProviderFailureKind.TRANSIENT);

        assertThat(PROPERTIES.baseBlockFor(AiProviderFailureKind.QUOTA)).isGreaterThan(transientBlock);
        assertThat(PROPERTIES.baseBlockFor(AiProviderFailureKind.AUTH)).isGreaterThan(transientBlock);
    }

    @Test
    void 창_판정은_최소_표본과_실패율_두_값으로_한다() {
        // 절대 건수 기준을 쓰지 않는다 — 호출량에 따라 같은 건수의 뜻이 달라지기 때문이다.
        assertThat(PROPERTIES.minimumNumberOfCalls()).isEqualTo(10);
        assertThat(PROPERTIES.failureRatePercent()).isEqualTo(50);
    }
}
