package com.readum.infrastructure.aiChat.metrics;

import com.readum.domain.aiChat.service.AiChatInFlightTurnRegistry;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * 진행 중 턴 상한을 운영에서 보기 위한 지표 등록. 진행 목록({@link AiChatInFlightTurnRegistry})은 도메인 상태라
 * Micrometer 를 알지 않고, 여기서 그 값을 읽어 계기에 연결한다.
 *
 * <p>두 지표 모두 <b>측정 도구가 값을 들고 있지 않다</b>. 진행 중 턴 수도 거절 누적 수도 진행 목록이 정본이고,
 * 여기서는 그 값을 그때그때 읽어 갈 함수만 등록한다. 그래서 등록과 실제 증가가 어긋날 일이 없다.
 *
 * <p>노출되는 이름(Prometheus 기준):
 * <ul>
 *   <li>{@code ai_chat_in_flight_turns} — 지금 진행 중인 턴 수</li>
 *   <li>{@code ai_chat_in_flight_rejections_total} — 상한에 걸려 거절한 누적 횟수
 *       (Micrometer 가 카운터에 {@code _total} 을 붙인다)</li>
 * </ul>
 * 이름은 Micrometer 관례대로 점으로 짓고, Prometheus 쪽 표기(밑줄)는 내보낼 때 변환된다.
 *
 * <p>이 두 값이 상한을 조정하는 근거다. 진행 중 턴 수의 최고치가 상한에 한참 못 미치는데 거절이 0 이면 상한이
 * 여유롭다는 뜻이고, 거절이 꾸준히 찍히면 상한이 실제 유입보다 낮거나 턴이 제때 끝나지 않는다는 신호다.
 */
@Configuration
public class AiChatInFlightTurnMetrics {

    private static final String IN_FLIGHT_TURNS = "ai.chat.in.flight.turns";
    private static final String IN_FLIGHT_REJECTIONS = "ai.chat.in.flight.rejections";

    public AiChatInFlightTurnMetrics(MeterRegistry meterRegistry, AiChatInFlightTurnRegistry inFlightTurnRegistry) {
        Gauge.builder(IN_FLIGHT_TURNS, inFlightTurnRegistry, AiChatInFlightTurnRegistry::inFlightCount)
                .description("지금 진행 중인 채팅 턴 수 (선행 처리 시작 ~ 마지막 후처리)")
                .baseUnit("turns")
                .register(meterRegistry);

        FunctionCounter.builder(IN_FLIGHT_REJECTIONS, inFlightTurnRegistry,
                        AiChatInFlightTurnRegistry::capacityRejectionCount)
                .description("진행 중 턴 상한에 걸려 503 으로 거절한 누적 횟수")
                .baseUnit("rejections")
                .register(meterRegistry);
    }
}
