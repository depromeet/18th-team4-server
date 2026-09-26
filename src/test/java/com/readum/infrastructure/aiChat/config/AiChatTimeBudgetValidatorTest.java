package com.readum.infrastructure.aiChat.config;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.infrastructure.ai.openai.OpenAiHttpClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
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

    private static final Duration MODERATION_HTTP_CEILING = Duration.ofSeconds(6);
    private static final Duration CONNECTION_ACQUIRE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REDIS_COMMAND_TIMEOUT = Duration.ofSeconds(1);

    @Test
    void 무응답_기한이_생성_전체_기한보다_길면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 30, 30, 10);

        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT, REDIS_COMMAND_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation-idle-timeout-seconds(30)")
                .hasMessageContaining("generation-total-timeout-seconds(20)");
    }

    @Test
    void 전달_기한이_생성_전체_기한보다_길지_않으면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 20, 10);

        // 같으면 완성본 교체(replace)가 나갈 시간이 0 이라 상한 구실을 못 한다.
        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT, REDIS_COMMAND_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivery-timeout-seconds(20)")
                .hasMessageContaining("generation-total-timeout-seconds(20)");
    }

    @Test
    void moderation_HTTP_상한이_선행_처리_여유_안에_들지_않으면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(6, 20, 10, 30, 10);

        // 선행 여유를 moderation 상한과 같게 두면 Redis·DB 몫이 남지 않는다.
        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT, REDIS_COMMAND_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연결+읽기(6초)")
                .hasMessageContaining("prepare-allowance-seconds(6)");
    }

    @Test
    void Redis_명령_기한_두_번과_moderation_상한이_선행_처리_여유_안에_들지_않으면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 30, 10);

        // 매달린 Redis 앞에서 fail-open 은 명령 기한만큼 기다린 뒤에야 발동한다 — 그 몫이 선행 여유에 들어야 한다.
        // 명령 기한 2초면 호출 둘이 4초, moderation 6초와 합쳐 10초라 선행 여유 10초를 그대로 다 쓴다.
        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.timeout(2000ms)")
                .hasMessageContaining("Redis 호출 2회")
                .hasMessageContaining("prepare-allowance-seconds(10)");
    }

    @Test
    void Redis_명령_기한을_읽지_못하면_그_조건만_건너뛰고_통과한다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 30, 10);

        assertThatCode(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, CONNECTION_ACQUIRE_TIMEOUT, null))
                .doesNotThrowAnyException();
    }

    @Test
    void DB_연결_획득_상한이_후처리_여유_안에_들지_않으면_기동을_막는다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 30, 10);

        assertThatThrownBy(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, Duration.ofSeconds(30), REDIS_COMMAND_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection-timeout(30000ms)")
                .hasMessageContaining("post-processing-allowance-seconds(10)");
    }

    @Test
    void 연결_획득_상한을_읽지_못하면_그_조건만_건너뛰고_통과한다() {
        AiChatProperties.Streaming streaming = streaming(10, 20, 10, 30, 10);

        assertThatCode(() -> AiChatTimeBudgetValidator.verify(
                streaming, MODERATION_HTTP_CEILING, null, REDIS_COMMAND_TIMEOUT))
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
                productionConnectionAcquireTimeout(),
                productionRedisCommandTimeout()))
                .doesNotThrowAnyException();

        // 한 턴의 시간 예산은 40초다 — 값이 바뀌면 문서(docs/domain/ai-chat.md · docs/ops/…)도 함께 고친다.
        assertThat(streaming.turnRequestExpiryTimeout()).isEqualTo(Duration.ofSeconds(40));
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

    /** Redis 명령 기한은 환경 무관 상수라 세 프로파일이 아니라 {@code application.yml} 한 곳에 있다. */
    @SuppressWarnings("unchecked")
    private static Duration productionRedisCommandTimeout() throws Exception {
        try (InputStream applicationYaml = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> root = new Yaml().load(applicationYaml);
            Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("data");
            Map<String, Object> redis = (Map<String, Object>) data.get("redis");
            Object timeout = redis.get("timeout");
            assertThat(timeout).as("application.yml 의 spring.data.redis.timeout").isNotNull();
            return DurationStyle.detectAndParse(timeout.toString());
        }
    }

    private static int intValue(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        assertThat(value).as("application.yml 의 ai-chat.streaming.%s", key).isNotNull();
        return ((Number) value).intValue();
    }
}
