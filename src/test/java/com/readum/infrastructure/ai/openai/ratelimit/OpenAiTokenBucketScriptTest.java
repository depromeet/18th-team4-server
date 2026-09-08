package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code redis/openai-token-bucket.lua} 의 계약을 <b>실제 Redis</b> 위에서 검증한다.
 *
 * <p>게이트 클래스를 거치지 않고 스크립트를 직접 실행한다 — 검증 대상이 자바 쪽 인자 계산이 아니라
 * 스크립트의 보충·차감·대기 계산이라서다. 현재 시각은 앱이 ARGV 로 넘기는 계약이므로, 잠들지 않고
 * {@code now} 값을 올려 시간을 흘린다.
 *
 * <p>{@code redis-server} 바이너리가 PATH 에 있을 때만 돈다(없으면 건너뜀). 빈 포트에 저장 없이 띄우고
 * 클래스가 끝나면 내린다.
 */
class OpenAiTokenBucketScriptTest {

    private static final String REQUEST_BUCKET_KEY = "test:bucket:rpm";
    private static final String TOKEN_BUCKET_KEY = "test:bucket:tpm";
    private static final List<String> KEYS = List.of(REQUEST_BUCKET_KEY, TOKEN_BUCKET_KEY);

    // 계산이 눈으로 따라가지는 값 — 요청 60 rpm(0.001 건/ms) · 버킷 3건, 토큰 600 tpm(0.01 토큰/ms) · 버킷 100토큰
    private static final long REQUEST_CAPACITY = 3;
    private static final double REQUEST_RATE_PER_MILLIS = 0.001;
    private static final long TOKEN_CAPACITY = 100;
    private static final double TOKEN_RATE_PER_MILLIS = 0.01;
    private static final long TTL_MILLIS = 20_000;
    private static final long START_MILLIS = 1_700_000_000_000L;

    private static Process redisProcess;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static DefaultRedisScript<Long> script;

    @BeforeAll
    static void startRedis() throws Exception {
        Path redisServer = findRedisServer();
        Assumptions.assumeTrue(redisServer != null, "PATH 에 redis-server 가 없어 건너뜀");

        int port = freePort();
        redisProcess = new ProcessBuilder(
                redisServer.toString(), "--port", String.valueOf(port),
                "--bind", "127.0.0.1", "--save", "", "--appendonly", "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();

        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        awaitReady();

        script = new DefaultRedisScript<>(scriptText(), Long.class);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisProcess != null) {
            redisProcess.destroy();
            try {
                if (!redisProcess.waitFor(3, TimeUnit.SECONDS)) {
                    redisProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                redisProcess.destroyForcibly();
            }
        }
    }

    @BeforeEach
    void clearBuckets() {
        template.delete(KEYS);
    }

    // --- 계약 ------------------------------------------------------------------------------------

    @Test
    void 키가_없으면_가득_찬_버킷으로_보고_통과시키며_뺀_상태와_시각을_저장한다() {
        assertThat(acquire(START_MILLIS, 10)).isZero();

        assertThat(tokensIn(REQUEST_BUCKET_KEY)).isEqualTo(REQUEST_CAPACITY - 1);
        assertThat(tokensIn(TOKEN_BUCKET_KEY)).isEqualTo(TOKEN_CAPACITY - 10);
        assertThat(template.opsForHash().get(REQUEST_BUCKET_KEY, "ts")).isEqualTo(String.valueOf(START_MILLIS));
        assertThat(template.getExpire(REQUEST_BUCKET_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, TTL_MILLIS);
        assertThat(template.getExpire(TOKEN_BUCKET_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, TTL_MILLIS);
    }

    @Test
    void 반복_통과로_비우면_부족량을_보충_속도로_나눈_대기_ms_를_돌려주고_아무것도_빼지_않는다() {
        drainRequestBucket(START_MILLIS);

        // 요청 버킷 0건 → 1건 차려면 1 ÷ 0.001 = 1000ms. 토큰은 70 남아 여유.
        assertThat(acquire(START_MILLIS, 10)).isEqualTo(1000L);

        assertThat(tokensIn(REQUEST_BUCKET_KEY)).isZero();
        assertThat(tokensIn(TOKEN_BUCKET_KEY)).isEqualTo(TOKEN_CAPACITY - 30);
    }

    @Test
    void 돌려준_대기_ms_만큼_시간을_흘리면_통과한다() {
        drainRequestBucket(START_MILLIS);
        long waitMillis = acquire(START_MILLIS, 10);

        assertThat(acquire(START_MILLIS + waitMillis - 1, 10)).isPositive();
        assertThat(acquire(START_MILLIS + waitMillis, 10)).isZero();
    }

    @Test
    void 조용히_오래_두어도_버킷_크기_이상은_쌓이지_않는다() {
        drainRequestBucket(START_MILLIS);

        long muchLater = START_MILLIS + Duration.ofHours(1).toMillis();
        for (int passed = 0; passed < REQUEST_CAPACITY; passed++) {
            assertThat(acquire(muchLater, 10)).as("보충 후 %d번째 통과", passed + 1).isZero();
        }
        assertThat(acquire(muchLater, 10)).as("크기만큼 쓰고 나면 다시 기다린다").isPositive();
    }

    @Test
    void 필요량이_버킷_크기보다_크면_가득_찼을_때만_통과시키고_음수로_빼서_뒤따르는_호출이_더_기다린다() {
        int oversized = (int) TOKEN_CAPACITY + 50;

        assertThat(acquire(START_MILLIS, oversized)).isZero();
        assertThat(tokensIn(TOKEN_BUCKET_KEY)).isEqualTo(-50);

        // 토큰 1개가 차려면 (1 − (−50)) ÷ 0.01 = 5100ms — 빚진 만큼 더 기다린다.
        assertThat(acquire(START_MILLIS, 1)).isEqualTo(5100L);
        // 가득 차지 않은 버킷에는 큰 요청이 다시 들어올 수 없다 — 다시 가득 차는 (100 + 50) ÷ 0.01 = 15000ms.
        assertThat(acquire(START_MILLIS, oversized)).isEqualTo(15000L);
        assertThat(acquire(START_MILLIS + 15000, oversized)).isZero();
    }

    @Test
    void 요청_버킷과_토큰_버킷_중_더_긴_대기를_돌려준다() {
        drainRequestBucket(START_MILLIS);   // 요청 0건(대기 1000ms), 토큰 70개

        // 토큰 90개 필요 → 20개 부족 ÷ 0.01 = 2000ms 가 요청 쪽 1000ms 보다 길다.
        assertThat(acquire(START_MILLIS, 90)).isEqualTo(2000L);
        // 토큰 10개면 토큰 쪽은 여유라 요청 쪽 1000ms 가 남는다.
        assertThat(acquire(START_MILLIS, 10)).isEqualTo(1000L);
    }

    @Test
    void 통과하지_못해도_보충한_상태와_시각을_저장해_같은_시간을_두_번_보충하지_않는다() {
        drainRequestBucket(START_MILLIS);
        long halfWay = START_MILLIS + 500;

        assertThat(acquire(halfWay, 10)).isEqualTo(500L);

        // 거절됐지만 500ms 치 보충(0.5건)과 시각이 저장돼 있다.
        assertThat(template.opsForHash().get(REQUEST_BUCKET_KEY, "ts")).isEqualTo(String.valueOf(halfWay));
        assertThat(tokensIn(REQUEST_BUCKET_KEY)).isEqualTo(0.5);
        // 같은 시각에 다시 두드리면 지난 시간이 0 이라 같은 대기가 나온다 — 두 번 보충됐다면 통과했을 것이다.
        assertThat(acquire(halfWay, 10)).isEqualTo(500L);
        assertThat(acquire(halfWay + 500, 10)).isZero();
    }

    @Test
    void hash_의_tokens_필드에_보상해_돌려주면_다시_통과한다() {
        drainRequestBucket(START_MILLIS);
        assertThat(acquire(START_MILLIS, 10)).isPositive();

        // 게이트의 compensate 가 하는 일 — HINCRBYFLOAT 로 요청 1건을 되돌린다.
        template.opsForHash().increment(REQUEST_BUCKET_KEY, "tokens", 1.0d);

        assertThat(acquire(START_MILLIS, 10)).isZero();
        assertThat(acquire(START_MILLIS, 10)).isPositive();
    }

    // --- 도우미 ----------------------------------------------------------------------------------

    private static long acquire(long nowMillis, int tokensNeeded) {
        Long waitMillis = template.execute(
                script, KEYS,
                String.valueOf(nowMillis),
                String.valueOf(REQUEST_CAPACITY),
                String.valueOf(REQUEST_RATE_PER_MILLIS),
                String.valueOf(TOKEN_CAPACITY),
                String.valueOf(TOKEN_RATE_PER_MILLIS),
                String.valueOf(tokensNeeded),
                String.valueOf(TTL_MILLIS));
        assertThat(waitMillis).isNotNull();
        return waitMillis;
    }

    /** 요청 버킷을 크기만큼 통과시켜 0건으로 만든다. 토큰은 호출당 10개씩 쓴다. */
    private static void drainRequestBucket(long nowMillis) {
        for (int passed = 0; passed < REQUEST_CAPACITY; passed++) {
            assertThat(acquire(nowMillis, 10)).as("비우는 중 %d번째 통과", passed + 1).isZero();
        }
    }

    private static double tokensIn(String bucketKey) {
        Object stored = template.opsForHash().get(bucketKey, "tokens");
        assertThat(stored).as("%s 의 tokens 필드", bucketKey).isNotNull();
        return Double.parseDouble(stored.toString());
    }

    // --- Redis 띄우기 ----------------------------------------------------------------------------

    private static Path findRedisServer() {
        List<String> directories = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            directories.addAll(List.of(path.split(java.io.File.pathSeparator)));
        }
        for (String directory : directories) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, "redis-server");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return serverSocket.getLocalPort();
        }
    }

    private static void awaitReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!redisProcess.isAlive()) {
                throw new IllegalStateException("redis-server 가 기동 중 종료됐습니다 (exit " + redisProcess.exitValue() + ")");
            }
            try {
                template.getConnectionFactory().getConnection().ping();
                return;
            } catch (Exception notYet) {
                lastFailure = notYet;
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("redis-server 가 10초 안에 준비되지 않았습니다", lastFailure);
    }

    private static String scriptText() throws IOException {
        try (InputStream scriptStream = new ClassPathResource("redis/openai-token-bucket.lua").getInputStream()) {
            return new String(scriptStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
