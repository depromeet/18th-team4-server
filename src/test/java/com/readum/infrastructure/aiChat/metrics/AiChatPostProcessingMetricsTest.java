package com.readum.infrastructure.aiChat.metrics;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.service.AiChatPostProcessingRetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시도가 일시 실패를 흡수하고 있는지, 아니면 사용자가 답변을 잃고 있는지를 이 두 지표로 판단하므로
 * <b>이름과 태그</b>가 약속대로인지 확인한다. Prometheus 로 내보낼 때의 표기는
 * {@code ai_chat_post_processing_retries_total} · {@code ai_chat_post_processing_failures_total} 이다
 * (카운터의 {@code _total} 은 Micrometer 가 붙인다).
 *
 * <p>값이 실제로 따라 오르는지는 {@code AiChatPostProcessingRetryTest} 가 재시도 규칙 쪽에서 확인한다 —
 * 규칙을 돌리는 입구({@code run})의 인자·반환 타입이 도메인 패키지 안에서만 보이기 때문이다.
 * 여기서 확인할 것은 <b>계기가 값을 따로 들고 있지 않고 규칙의 누적값을 읽어 간다</b>는 배선이다.
 */
class AiChatPostProcessingMetricsTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void 다시_시도한_횟수와_끝내_실패한_수를_종료별로_노출한다() {
        AiChatPostProcessingRetry postProcessingRetry = new AiChatPostProcessingRetry(properties());
        new AiChatPostProcessingMetrics(meterRegistry, postProcessingRetry);

        assertThat(retryCount()).isEqualTo(postProcessingRetry.retryCount());
        assertThat(failureCount("success_commit")).isEqualTo(postProcessingRetry.failureCount(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT));
        assertThat(failureCount("without_charge")).isEqualTo(postProcessingRetry.failureCount(
                AiChatPostProcessingRetry.FinishKind.WITHOUT_CHARGE));

        // 끝내 실패한 수는 종료별로 갈라 세므로, 태그 값이 빠지면 두 종료가 한 줄로 합쳐진다.
        assertThat(meterRegistry.find("ai.chat.post.processing.failures").functionCounters())
                .hasSize(AiChatPostProcessingRetry.FinishKind.values().length);
    }

    private static AiChatProperties properties() {
        return new AiChatProperties(
                new AiChatProperties.Context(8000, 2000, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(120000, 512),
                new AiChatProperties.Streaming(20, 10, 30, 60, 10, 20, 256, 300));
    }

    private double retryCount() {
        return meterRegistry.get("ai.chat.post.processing.retries").functionCounter().count();
    }

    private double failureCount(String outcome) {
        return meterRegistry.get("ai.chat.post.processing.failures")
                .tag("outcome", outcome).functionCounter().count();
    }
}
