package com.readum.infrastructure.aiChat.metrics;

import com.readum.domain.aiChat.service.AiChatPostProcessingRetry;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * 후처리 종료 트랜잭션의 유계 재시도를 운영에서 보기 위한 지표 등록. 재시도 규칙
 * ({@link AiChatPostProcessingRetry})은 도메인이라 Micrometer 를 알지 않고, 여기서 그 누적값을 읽어 계기에 연결한다
 * (진행 중 턴 지표와 같은 방식).
 *
 * <p>둘 다 <b>측정 도구가 값을 들고 있지 않다</b> — 정본은 재시도 규칙 쪽 누적값이고, 여기서는 그 값을
 * 그때그때 읽어 갈 함수만 등록한다.
 *
 * <p>노출되는 이름(Prometheus 기준):
 * <ul>
 *   <li>{@code ai_chat_post_processing_retries_total} — 종료 트랜잭션을 다시 시도한 누적 횟수</li>
 *   <li>{@code ai_chat_post_processing_failures_total} — 다시 시도하고도 끝내 실패한 후처리 수.
 *       태그 {@code outcome} 이 {@code success_commit}(성공 확정)과 {@code without_charge}(청구 없는 종료)를
 *       가른다. 다시 시도할 예외가 아니어서 곧바로 실패한 것도 여기 들어간다 — 운영에서 알고 싶은 것은
 *       "이 턴이 저장되지 못했다" 이지 "몇 번 시도했는가" 가 아니기 때문이다.</li>
 * </ul>
 * (카운터의 {@code _total} 은 Micrometer 가 붙인다.)
 *
 * <p>읽는 법: 재시도가 꾸준히 찍히는데 실패가 0 이면 일시 실패를 규칙이 흡수하고 있다는 뜻이다.
 * 실패가 찍히면 그 수만큼 사용자가 답변을 받고도 저장되지 않아 새 요청 식별자로 다시 생성해야 했다는 뜻이다 —
 * 완성본을 따로 보관하지 않기 때문이다.
 */
@Configuration
public class AiChatPostProcessingMetrics {

    private static final String POST_PROCESSING_RETRIES = "ai.chat.post.processing.retries";
    private static final String POST_PROCESSING_FAILURES = "ai.chat.post.processing.failures";

    public AiChatPostProcessingMetrics(
            MeterRegistry meterRegistry, AiChatPostProcessingRetry postProcessingRetry) {
        FunctionCounter.builder(POST_PROCESSING_RETRIES, postProcessingRetry,
                        AiChatPostProcessingRetry::retryCount)
                .description("후처리 종료 트랜잭션을 다시 시도한 누적 횟수")
                .baseUnit("retries")
                .register(meterRegistry);

        for (AiChatPostProcessingRetry.FinishKind finishKind : AiChatPostProcessingRetry.FinishKind.values()) {
            FunctionCounter.builder(POST_PROCESSING_FAILURES, postProcessingRetry,
                            retry -> retry.failureCount(finishKind))
                    .tag("outcome", finishKind.tagValue())
                    .description("다시 시도하고도 끝내 실패한 후처리 수")
                    .baseUnit("failures")
                    .register(meterRegistry);
        }
    }
}
