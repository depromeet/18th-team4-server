package com.readum.infrastructure.aiChat.config;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.infrastructure.ai.openai.OpenAiHttpClientConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * 한 턴의 시간 예산이 앞뒤가 맞는지 기동 시 대조하고, 어긋나면 기동을 막는다
 * (moderation 카테고리 레지스트리의 기동 검증과 같은 방식).
 *
 * <p><b>무엇을 막는가.</b> 한 턴은 접수부터 {@code expires_at}(= 선행 여유 + 생성 전체 기한 + 후처리 여유)
 * 안에 끝나거나 어느 구간에서든 명시적으로 실패해야 한다. 그런데 구간별 상한은 서로 다른 곳에 흩어져 있다 —
 * 채팅 기한은 {@code ai-chat.streaming}, moderation 의 HTTP 상한은 {@link OpenAiHttpClientConfig},
 * DB 연결을 빌리는 상한은 {@code spring.datasource.hikari.connection-timeout} 이다.
 * 한 곳만 바꾸면 "정상으로 끝날 수 있는 최장 시간 > 만료" 가 <b>조용히</b> 생겨, 정상 처리 중인 요청을
 * 미정산 예약 반환이 가로채 환불한다. 그 어긋남을 첫 기동에서 드러내는 것이 이 빈의 일이다.
 *
 * <p>이 빈이 <b>infrastructure</b> 에 있는 이유: 대조할 값 셋 중 둘(moderation HTTP 상한 · Hikari 연결 획득
 * 상한)이 인프라 쪽 설정이라, 도메인이 인프라를 거꾸로 참조하지 않게 하려면 대조가 이쪽에 있어야 한다.
 */
@Slf4j
@Component
public class AiChatTimeBudgetValidator {

    private final AiChatProperties.Streaming streaming;
    private final Duration moderationHttpCeiling;
    private final Duration connectionAcquireTimeout;

    public AiChatTimeBudgetValidator(AiChatProperties aiChatProperties, DataSource dataSource) {
        this.streaming = aiChatProperties.streaming();
        this.moderationHttpCeiling = OpenAiHttpClientConfig.moderationHttpCeiling();
        this.connectionAcquireTimeout = connectionAcquireTimeout(dataSource);
    }

    @PostConstruct
    void validateTimeBudget() {
        verify(streaming, moderationHttpCeiling, connectionAcquireTimeout);
        log.info("[AI 채팅] 한 턴의 시간 예산 {}초 = 선행 여유 {} + 생성 전체 기한 {} + 후처리 여유 {} "
                        + "(moderation HTTP 상한 {}초, DB 연결 획득 상한 {})",
                streaming.turnRequestExpiryTimeout().toSeconds(),
                streaming.prepareAllowanceSeconds(),
                streaming.generationTotalTimeoutSeconds(),
                streaming.postProcessingAllowanceSeconds(),
                moderationHttpCeiling.toSeconds(),
                connectionAcquireTimeout == null ? "읽지 못함" : connectionAcquireTimeout.toMillis() + "ms");
    }

    /**
     * 네 조건을 차례로 본다. 어긋나면 값과 식을 그대로 담은 {@link IllegalStateException} 을 던져 기동을 막는다.
     *
     * @param connectionAcquireTimeout Hikari 연결 획득 상한. 읽지 못했으면 {@code null} — 그 조건만 건너뛴다
     */
    static void verify(
            AiChatProperties.Streaming streaming,
            Duration moderationHttpCeiling,
            Duration connectionAcquireTimeout
    ) {
        // 1) 무응답 기한이 생성 전체 기한보다 길면 영영 발동하지 않는다 — 상한 구실을 못 한다.
        if (streaming.generationIdleTimeoutSeconds() > streaming.generationTotalTimeoutSeconds()) {
            throw new IllegalStateException(
                    "무응답 기한이 생성 전체 기한보다 깁니다: generation-idle-timeout-seconds(%d) <= generation-total-timeout-seconds(%d) 여야 합니다."
                            .formatted(streaming.generationIdleTimeoutSeconds(),
                                    streaming.generationTotalTimeoutSeconds()));
        }

        // 2) 전달 기한이 생성 전체 기한보다 길어야 완성본 교체(replace)가 나갈 시간이 남는다.
        if (streaming.deliveryTimeoutSeconds() <= streaming.generationTotalTimeoutSeconds()) {
            throw new IllegalStateException(
                    "전달 기한이 생성 전체 기한보다 길지 않습니다: delivery-timeout-seconds(%d) > generation-total-timeout-seconds(%d) 여야 완성본 교체가 나갈 시간이 남습니다."
                            .formatted(streaming.deliveryTimeoutSeconds(),
                                    streaming.generationTotalTimeoutSeconds()));
        }

        // 3) moderation HTTP 호출 하나가 선행 처리 여유를 다 써 버리면, 정상적인 선행 처리가 만료 뒤에 끝난다.
        long moderationCeilingSeconds = moderationHttpCeiling.toSeconds();
        if (moderationCeilingSeconds >= streaming.prepareAllowanceSeconds()) {
            throw new IllegalStateException(
                    "moderation HTTP 상한이 선행 처리 여유 안에 들지 않습니다: 연결+읽기(%d초) < prepare-allowance-seconds(%d) 여야 합니다. 상한은 OpenAiHttpClientConfig 에 있습니다."
                            .formatted(moderationCeilingSeconds, streaming.prepareAllowanceSeconds()));
        }

        // 4) DB 연결을 빌리는 데만 후처리 여유를 다 쓰면, 저장·정산이 시작도 못 한 채 만료된다.
        if (connectionAcquireTimeout == null) {
            log.warn("[AI 채팅] Hikari 연결 획득 상한을 읽지 못해 후처리 여유와 대조하지 못했습니다 — "
                    + "DataSource 가 HikariDataSource 가 아닙니다. 문서(docs/ops/ai-chat-shutdown-and-recovery.md)로 확인하세요.");
            return;
        }
        long postProcessingAllowanceMillis = streaming.postProcessingAllowanceSeconds() * 1_000L;
        if (connectionAcquireTimeout.toMillis() >= postProcessingAllowanceMillis) {
            throw new IllegalStateException(
                    "DB 연결 획득 상한이 후처리 여유 안에 들지 않습니다: spring.datasource.hikari.connection-timeout(%dms) < post-processing-allowance-seconds(%d) × 1000 = %dms 여야 합니다."
                            .formatted(connectionAcquireTimeout.toMillis(),
                                    streaming.postProcessingAllowanceSeconds(), postProcessingAllowanceMillis));
        }
    }

    /** Hikari 가 아닌 DataSource(테스트 대역 등)에서는 읽을 값이 없다 — 그때는 {@code null} 을 돌려 조건 4 를 건너뛴다. */
    private static Duration connectionAcquireTimeout(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return Duration.ofMillis(hikariDataSource.getConnectionTimeout());
        }
        return null;
    }
}
