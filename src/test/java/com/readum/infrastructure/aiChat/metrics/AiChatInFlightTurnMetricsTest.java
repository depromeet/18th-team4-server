package com.readum.infrastructure.aiChat.metrics;

import com.readum.domain.aiChat.service.AiChatInFlightTurnRegistry;
import com.readum.domain.exception.ServiceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상한을 조정하는 절차가 이 두 지표를 읽는 것이라, 이름과 값이 진행 목록을 따라가는지 확인한다.
 * Prometheus 로 내보낼 때의 표기는 {@code ai_chat_in_flight_turns} ·
 * {@code ai_chat_in_flight_rejections_total} 이다(카운터의 {@code _total} 은 Micrometer 가 붙인다).
 */
class AiChatInFlightTurnMetricsTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void 진행_중_턴_수와_상한_거절_수를_진행_목록에서_그때그때_읽어_노출한다() {
        AiChatInFlightTurnRegistry inFlightTurnRegistry = new AiChatInFlightTurnRegistry(2);
        new AiChatInFlightTurnMetrics(meterRegistry, inFlightTurnRegistry);

        assertThat(gaugeValue()).isZero();
        assertThat(rejectionCount()).isZero();

        AiChatInFlightTurnRegistry.InFlightTurn first = inFlightTurnRegistry.register("요청-1", 10L, 100L);
        inFlightTurnRegistry.register("요청-2", 11L, 101L);
        assertThat(gaugeValue()).isEqualTo(2.0);

        assertThatThrownBy(() -> inFlightTurnRegistry.register("요청-3", 12L, 102L))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(rejectionCount()).isEqualTo(1.0);

        // 지표가 값을 따로 들고 있지 않다는 것 — 진행 목록이 줄면 계기도 같이 준다.
        inFlightTurnRegistry.finish(first);
        assertThat(gaugeValue()).isEqualTo(1.0);
        assertThat(rejectionCount()).isEqualTo(1.0);
    }

    private double gaugeValue() {
        return meterRegistry.get("ai.chat.in.flight.turns").gauge().value();
    }

    private double rejectionCount() {
        return meterRegistry.get("ai.chat.in.flight.rejections").functionCounter().count();
    }
}
