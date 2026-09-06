package com.readum.infrastructure.aiChat.config;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.infrastructure.ai.openai.OpenAiHttpClientConfig;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기동 시 시간 예산 검증의 단위 테스트. 조건 하나씩 어긋난 설정을 넣어 기동이 막히는지 보고,
 * 마지막에 <b>운영 yml 에 지금 적힌 값</b>이 그대로 통과하는지 확인한다 — 값을 여기 베껴 두면
 * yml 만 바뀌었을 때 테스트가 옛 조합을 계속 통과시킨다.
 */
class AiChatTimeBudgetValidatorTest {

    private static final Duration MODERATION_HTTP_CEILING = Duration.ofSeconds(8);
    private static final Duration CONNECTION_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void 무응답_기한이_생성_전체_기한보다_길면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 30, 30, 20);

        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation-idle-timeout-seconds(30)")
                .hasMessageContaining("generation-total-timeout-seconds(20)");
    }

    @Test
    void 전달_기한이_생성_전체_기한보다_길지_않으면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 20, 20);

        // 같으면 완성본 교체(replace)가 나갈 시간이 0 이라 상한 구실을 못 한다.
        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivery-timeout-seconds(20)")
                .hasMessageContaining("generation-total-timeout-seconds(20)");
    }

    @Test
    void moderation_HTTP_상한이_선행_처리_여유_안에_들지_않으면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(8, 20, 10, 30, 20);

        // 선행 여유를 moderation 상한과 같게 두면 이력 조회·예약·게이트 몫이 남지 않는다.
        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연결+읽기(8초)")
                .hasMessageContaining("prepare-allowance-seconds(8)");
    }

    @Test
    void DB_연결_획득_상한이_후처리_여유_안에_들지_않으면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 30, 20);

        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection-timeout(30000ms)")
                .hasMessageContaining("post-processing-allowance-seconds(20)");
    }

    @Test
    void 연결_획득_상한을_읽지_못하면_그_조건만_건너뛰고_통과한다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 30, 20);

        assertThatCode(() -> AiChatTimeBudgetValidator.verify(streaming, MODERATION_HTTP_CEILING, null))
                .doesNotThrowAnyException();
    }

    @Test
    void 운영_yml_에_적힌_값과_코드의_구간별_상한은_서로_맞물린다() throws Exception {
        Map<String, Object> streamingYaml = productionStreamingProperties();
        AiChatProperties.Streaming streaming = streaming(
                intValue(streamingYaml, "prepare-allowance-seconds"),
                intValue(streamingYaml, "generation-total-timeout-seconds"),
                intValue(streamingYaml, "generation-idle-timeout-seconds"),
                intValue(streamingYaml, "delivery-timeout-seconds"),
                intValue(streamingYaml, "post-processing-allowance-seconds"));

        assertThatCode(() -> AiChatTimeBudgetValidator.verify(
                streaming,
                OpenAiHttpClientConfig.moderationHttpCeiling(),
                productionConnectionAcquireTimeout()))
                .doesNotThrowAnyException();

        // 한 턴의 시간 예산은 50초다 — 값이 바뀌면 문서(docs/domain/ai-chat.md · docs/ops/…)도 함께 고친다.
        assertThat(streaming.turnRequestExpiryTimeout()).isEqualTo(Duration.ofSeconds(50));
    }

    private static AiChatProperties.Streaming streaming(
            int prepareAllowanceSeconds,
            int generationTotalTimeoutSeconds,
            int generationIdleTimeoutSeconds,
            int deliveryTimeoutSeconds,
            int postProcessingAllowanceSeconds
    ) {
        return new AiChatProperties.Streaming(
                generationTotalTimeoutSeconds,
                generationIdleTimeoutSeconds,
                deliveryTimeoutSeconds,
                60,
                prepareAllowanceSeconds,
                postProcessingAllowanceSeconds,
                256,
                240);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> productionStreamingProperties() throws Exception {
        try (InputStream applicationYaml = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> root = new Yaml().load(applicationYaml);
            Map<String, Object> aiChat = (Map<String, Object>) root.get("ai-chat");
            return (Map<String, Object>) aiChat.get("streaming");
        }
    }

    /** 세 MySQL 프로파일이 같은 값을 쓰므로 local 하나만 읽는다 — 갈라지면 아래 단언이 먼저 깨진다. */
    @SuppressWarnings("unchecked")
    private static Duration productionConnectionAcquireTimeout() throws Exception {
        try (InputStream localYaml = Files.newInputStream(Path.of("src/main/resources/application-local.yml"))) {
            Map<String, Object> root = new Yaml().load(localYaml);
            Map<String, Object> datasource =
                    (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("datasource");
            Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
            return Duration.ofMillis(((Number) hikari.get("connection-timeout")).longValue());
        }
    }

    private static int intValue(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        assertThat(value).as("application.yml 의 ai-chat.streaming.%s", key).isNotNull();
        return ((Number) value).intValue();
    }
}
