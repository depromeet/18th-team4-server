package com.readum.infrastructure.redis;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.out.UserMessageRateLimiter;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiGateProperties;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>매달린 Redis 앞에서 fail-open 이 몇 초 안에 발동하는지</b>를 실측한다.
 *
 * <p>폭주 가드({@link UserMessageRateLimiterRedisAdapter})와 전역 게이트({@link OpenAiRequestGate})는
 * Redis 장애를 만나면 검사 없이 통과시킨다. 그런데 그 통과는 <b>호출이 실패했다는 것을 안 뒤</b>에
 * 일어나므로, Redis 가 응답 없이 매달리면 통과까지 걸리는 시간이 곧 명령 기한이다. 기한을 두지 않으면
 * Lettuce 기본 60초가 걸려, 선행 처리의 Redis 호출 둘이 2분을 쓰고 한 턴의 시간 예산 40초를 넘긴다.
 * {@code spring.data.redis.timeout} / {@code connect-timeout} 을 1초로 못박은 것이 그 대비이고,
 * 이 테스트가 그 값이 실제로 걸리는지를 잰다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — {@link LettuceConnectionFactory} 와 두 협력자를 직접 조립한다.
 * 기한 값은 여기 베끼지 않고 {@code application.yml} 에서 읽어, yml 만 바뀌면 이 테스트가 먼저 깨지게 한다.
 */
class RedisFailOpenTimingTest {

    /** 매달림을 확실히 넘기고 정상 실패는 잡아내는 폭 — 기한 1초 기준. CI 흔들림을 견디도록 넉넉히 잡는다. */
    private static final Duration FAIL_OPEN_LOWER_BOUND = Duration.ofMillis(500);
    private static final Duration FAIL_OPEN_UPPER_BOUND = Duration.ofSeconds(3);

    private final List<Closeable> openedResources = new ArrayList<>();

    @AfterEach
    void closeOpenedResources() {
        for (int index = openedResources.size() - 1; index >= 0; index--) {
            try {
                openedResources.get(index).close();
            } catch (IOException ignored) {
                // 정리 실패는 테스트 결과와 무관하다
            }
        }
        openedResources.clear();
    }

    @Test
    void 연결은_받되_응답하지_않는_Redis_앞에서_폭주_가드는_약_1초_만에_검사_없이_통과시킨다() throws Exception {
        SilentRedis silentRedis = startSilentRedis();
        StringRedisTemplate template = templateFor(silentRedis.port());

        Measured<UserMessageRateLimiter.Result> measured = measure(() -> rateLimiter(template).tryConsume(7L));

        assertThat(measured.value()).isInstanceOf(UserMessageRateLimiter.Result.Bypassed.class);
        assertThat(measured.elapsed())
                .as("매달린 Redis 앞에서 폭주 가드가 통과 판정을 내리기까지 걸린 시간 (실측 %dms)",
                        measured.elapsed().toMillis())
                .isBetween(FAIL_OPEN_LOWER_BOUND, FAIL_OPEN_UPPER_BOUND);
    }

    @Test
    void 연결은_받되_응답하지_않는_Redis_앞에서_전역_게이트는_약_1초_만에_계상_없이_통과시킨다() throws Exception {
        SilentRedis silentRedis = startSilentRedis();
        StringRedisTemplate template = templateFor(silentRedis.port());

        Measured<OpenAiRequestGate.Decision> measured =
                measure(() -> gate(template).tryAcquire("gpt-4o-mini", 512));

        assertThat(measured.value()).isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
        assertThat(measured.elapsed())
                .as("매달린 Redis 앞에서 전역 게이트가 통과 판정을 내리기까지 걸린 시간 (실측 %dms)",
                        measured.elapsed().toMillis())
                .isBetween(FAIL_OPEN_LOWER_BOUND, FAIL_OPEN_UPPER_BOUND);
    }

    @Test
    void 연결_자체가_매달리는_Redis_앞에서도_두_호출_모두_기한_안에_통과시킨다() throws Exception {
        // accept 를 하지 않는 소켓의 대기열을 채워 두면 그 다음 연결은 SYN 이 버려져 매달린다.
        // 커널이 대기열을 넉넉히 잡아 연결이 맺어지더라도 응답이 없으므로, 이번엔 명령 기한이 같은 값으로 잡는다.
        UnacceptedRedis unacceptedRedis = startUnacceptedRedis();
        StringRedisTemplate template = templateFor(unacceptedRedis.port());

        Measured<UserMessageRateLimiter.Result> rateLimit = measure(() -> rateLimiter(template).tryConsume(7L));
        Measured<OpenAiRequestGate.Decision> gateDecision =
                measure(() -> gate(template).tryAcquire("gpt-4o-mini", 512));

        assertThat(rateLimit.value()).isInstanceOf(UserMessageRateLimiter.Result.Bypassed.class);
        assertThat(gateDecision.value()).isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
        assertThat(rateLimit.elapsed())
                .as("연결이 매달릴 때 폭주 가드가 통과 판정을 내리기까지 (실측 %dms)", rateLimit.elapsed().toMillis())
                .isBetween(FAIL_OPEN_LOWER_BOUND, FAIL_OPEN_UPPER_BOUND);
        assertThat(gateDecision.elapsed())
                .as("연결이 매달릴 때 전역 게이트가 통과 판정을 내리기까지 (실측 %dms)", gateDecision.elapsed().toMillis())
                .isBetween(FAIL_OPEN_LOWER_BOUND, FAIL_OPEN_UPPER_BOUND);
    }

    @Test
    void 죽은_Redis_는_연결_거부라서_기한을_기다리지_않고_즉시_통과시킨다() throws Exception {
        int deadPort = closedPort();
        StringRedisTemplate template = templateFor(deadPort);

        Measured<UserMessageRateLimiter.Result> rateLimit = measure(() -> rateLimiter(template).tryConsume(7L));
        Measured<OpenAiRequestGate.Decision> gateDecision =
                measure(() -> gate(template).tryAcquire("gpt-4o-mini", 512));

        // Redis 가 죽는 경우와 매달리는 경우의 차이가 여기서 드러난다 — 거부는 즉시 오므로 기한을 기다리지 않는다.
        assertThat(rateLimit.value()).isInstanceOf(UserMessageRateLimiter.Result.Bypassed.class);
        assertThat(gateDecision.value()).isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
        assertThat(rateLimit.elapsed())
                .as("연결 거부일 때 폭주 가드가 통과 판정을 내리기까지 (실측 %dms)", rateLimit.elapsed().toMillis())
                .isLessThan(FAIL_OPEN_LOWER_BOUND);
        assertThat(gateDecision.elapsed())
                .as("연결 거부일 때 전역 게이트가 통과 판정을 내리기까지 (실측 %dms)", gateDecision.elapsed().toMillis())
                .isLessThan(FAIL_OPEN_LOWER_BOUND);
    }

    /**
     * 기한을 두지 않았을 때의 비교 수치를 재는 테스트. 한 번 도는 데 1분이 넘어 기본 빌드에 넣을 수 없으므로
     * 꺼 둔다 — 값을 의심할 때만 손으로 켜서 돌린다. 2026-09-06 실측값은
     * {@code docs/ops/ai-chat-shutdown-and-recovery.md} 의 "Redis 의 대기 제한" 절에 적었다.
     */
    @Test
    @Disabled("기한 미설정(라이브러리 기본 60초)의 비교 수치 — 한 번에 1분 넘게 걸려 기본 빌드에서 제외한다")
    @DisplayName("기한을 두지 않으면 매달린 Redis 앞에서 fail-open 이 60초 뒤에야 발동한다 (비교 수치)")
    void 기한을_두지_않으면_fail_open_이_60초_뒤에야_발동한다() throws Exception {
        SilentRedis silentRedis = startSilentRedis();
        StringRedisTemplate template = template(silentRedis.port(), LettuceClientConfiguration.builder().build());

        Measured<UserMessageRateLimiter.Result> measured = measure(() -> rateLimiter(template).tryConsume(7L));

        assertThat(measured.value()).isInstanceOf(UserMessageRateLimiter.Result.Bypassed.class);
        assertThat(measured.elapsed())
                .as("기한 미설정일 때 폭주 가드가 통과 판정을 내리기까지 (실측 %dms)", measured.elapsed().toMillis())
                .isGreaterThan(Duration.ofSeconds(30));
    }

    // --- 협력자 조립 -------------------------------------------------------------------------

    private UserMessageRateLimiterRedisAdapter rateLimiter(StringRedisTemplate template) {
        AiChatProperties properties = new AiChatProperties(
                new AiChatProperties.Context(8000, 2000, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(120000, 512),
                new AiChatProperties.Streaming(20, 10, 30, 60, 10, 10, 256, 240)
        );
        return new UserMessageRateLimiterRedisAdapter(template, properties);
    }

    private OpenAiRequestGate gate(StringRedisTemplate template) {
        OpenAiGateProperties properties = new OpenAiGateProperties(
                Map.of("gpt-4o-mini", new OpenAiGateProperties.ModelLimit(9000, 180000L)), 300);
        return new OpenAiRequestGate(template, properties);
    }

    /** 운영이 실제로 쓰는 기한({@code application.yml})을 그대로 걸어 조립한다. */
    private StringRedisTemplate templateFor(int port) throws Exception {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(redisDuration("timeout"))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(redisDuration("connect-timeout"))
                                .build())
                        .build())
                .build();
        return template(port, clientConfiguration);
    }

    private StringRedisTemplate template(int port, LettuceClientConfiguration clientConfiguration) {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port), clientConfiguration);
        connectionFactory.afterPropertiesSet();
        openedResources.add(connectionFactory::destroy);
        return new StringRedisTemplate(connectionFactory);
    }

    /** 기한 값을 테스트에 베끼지 않는다 — yml 이 바뀌면 여기가 따라 바뀌어야 한다. */
    @SuppressWarnings("unchecked")
    private static Duration redisDuration(String key) throws Exception {
        try (InputStream applicationYaml = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> root = new Yaml().load(applicationYaml);
            Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("data");
            Map<String, Object> redis = (Map<String, Object>) data.get("redis");
            Object value = redis.get(key);
            assertThat(value).as("application.yml 의 spring.data.redis.%s", key).isNotNull();
            return DurationStyle.detectAndParse(value.toString());
        }
    }

    // --- 가짜 Redis ---------------------------------------------------------------------------

    private record SilentRedis(int port) {
    }

    private record UnacceptedRedis(int port) {
    }

    /** 연결은 받아 주되 한 바이트도 쓰지 않는 가짜 Redis — "살아 있지만 응답하지 않는" 상태를 만든다. */
    private SilentRedis startSilentRedis() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        openedResources.add(serverSocket);
        List<Socket> accepted = new ArrayList<>();
        Thread acceptor = Thread.ofVirtual().start(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    synchronized (accepted) {
                        accepted.add(socket);   // 닫지도 쓰지도 않고 그냥 붙잡아 둔다
                    }
                } catch (IOException stopped) {
                    return;
                }
            }
        });
        openedResources.add(() -> {
            acceptor.interrupt();
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
        });
        return new SilentRedis(serverSocket.getLocalPort());
    }

    /** accept 를 아예 하지 않고 대기열만 채워 둔 소켓 — 연결 맺기 자체가 매달리는 상태를 노린다. */
    private UnacceptedRedis startUnacceptedRedis() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        openedResources.add(serverSocket);
        int port = serverSocket.getLocalPort();
        for (int filler = 0; filler < 8; filler++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
                openedResources.add(socket);
            } catch (IOException alreadyFull) {
                socket.close();
                break;   // 대기열이 찼다 — 목적 달성
            }
        }
        return new UnacceptedRedis(port);
    }

    /** 아무도 듣지 않는 포트 — 연결이 즉시 거부된다. */
    private static int closedPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return serverSocket.getLocalPort();
        }
    }

    // --- 시간 재기 ---------------------------------------------------------------------------

    private record Measured<T>(T value, Duration elapsed) {
    }

    private static <T> Measured<T> measure(java.util.function.Supplier<T> call) {
        long startedAt = System.nanoTime();
        T value = call.get();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        System.out.printf("[실측] %s -> %s (%dms)%n",
                Thread.currentThread().getName(), value.getClass().getSimpleName(), elapsed.toMillis());
        return new Measured<>(value, elapsed);
    }
}
