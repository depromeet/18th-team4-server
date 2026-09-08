package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.File;
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
 * <p>{@code redis-server} 바이너리를 PATH 와 {@code /opt/homebrew/bin}·{@code /usr/bin} 에서 찾아
 * 있을 때만 돈다(없으면 건너뜀). 빈 포트에 저장 없이 띄우고 클래스가 끝나면 내린다.
 * CI 는 이 테스트가 조용히 건너뛰지 않도록 워크플로우에서 redis-server 를 설치한다.
 */
class OpenAiTokenBucketScriptTest {

    private static final String REQUEST_BUCKET_KEY = "test:bucket:rpm";
    private static final String TOKEN_BUCKET_KEY = "test:bucket:tpm";
    private static final List<String> KEYS = List.of(REQUEST_BUCKET_KEY, TOKEN_BUCKET_KEY);

    // 보충 속도는 2 의 거듭제곱 분수로 잡는다 — 이진수로 딱 떨어져 대기 ms 를 등호로 단언해도 오차가 없다.
    // 요청: 1/1024 건/ms(1건 차는 데 1024ms, 약 59 rpm) · 버킷 3건
    // 토큰: 1/128 토큰/ms(1개 차는 데 128ms, 약 469 tpm) · 버킷 100토큰
    private static final long REQUEST_CAPACITY = 3;
    private static final double REQUEST_RATE_PER_MILLIS = 1 / 1024.0;
    private static final long REQUEST_REFILL_MILLIS = 1024;
    private static final long TOKEN_CAPACITY = 100;
    private static final double TOKEN_RATE_PER_MILLIS = 1 / 128.0;
    private static final long TOKEN_REFILL_MILLIS = 128;
    /** ARGV[7] — 최소 TTL. 빚진 버킷은 이보다 오래 산다. */
    private static final long MINIMUM_TTL_MILLIS = 20_000;
    private static final long START_MILLIS = 1_700_000_000_000L;

    private static Process redisProcess;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static DefaultRedisScript<Long> script;

    @BeforeAll
    static void startRedis() throws Exception {
        Path redisServer = findRedisServer();
        Assumptions.assumeTrue(redisServer != null, "redis-server 를 찾지 못해 건너뜀");

        int port = freePort();
        redisProcess = new ProcessBuilder(
                redisServer.toString(), "--port", String.valueOf(port),
                "--bind", "127.0.0.1", "--save", "", "--appendonly", "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
        // JVM 이 강제 종료돼 @AfterAll 이 돌지 않아도 남은 프로세스가 떠 있지 않게 한다.
        Runtime.getRuntime().addShutdownHook(new Thread(redisProcess::destroy));

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
        assertThat(template.getExpire(REQUEST_BUCKET_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, MINIMUM_TTL_MILLIS);
        assertThat(template.getExpire(TOKEN_BUCKET_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, MINIMUM_TTL_MILLIS);
    }

    @Test
    void 반복_통과로_비우면_부족량을_보충_속도로_나눈_대기_ms_를_돌려주고_아무것도_빼지_않는다() {
        drainRequestBucket(START_MILLIS);

        // 요청 버킷 0건 → 1건 차려면 1024ms. 토큰은 70 남아 여유.
        assertThat(acquire(START_MILLIS, 10)).isEqualTo(REQUEST_REFILL_MILLIS);

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
    void 저장된_시각보다_이른_호출은_보충하지도_저장된_시각을_되돌리지도_않는다() {
        drainRequestBucket(START_MILLIS);   // 요청 0건, 시각 START

        // 시계가 뒤로 간 호출 — 지난 시간을 0 으로 보므로 보충이 없고, 대기는 빈 버킷 기준 그대로다.
        assertThat(acquire(START_MILLIS - 5_000, 10)).isEqualTo(REQUEST_REFILL_MILLIS);
        assertThat(tokensIn(REQUEST_BUCKET_KEY)).isZero();
        assertThat(template.opsForHash().get(REQUEST_BUCKET_KEY, "ts")).isEqualTo(String.valueOf(START_MILLIS));

        // 시각이 5초 뒤로 밀렸다면 다음 호출이 5초치를 더 보충받아 두 번 통과했을 것이다 — 1건치만 찼다.
        assertThat(acquire(START_MILLIS + REQUEST_REFILL_MILLIS, 10)).isZero();
        assertThat(acquire(START_MILLIS + REQUEST_REFILL_MILLIS, 10)).isPositive();
    }

    @Test
    void 필요량이_버킷_크기보다_크면_가득_찼을_때만_통과시키고_음수로_빼서_뒤따르는_호출이_더_기다린다() {
        int oversized = (int) TOKEN_CAPACITY + 50;

        assertThat(acquire(START_MILLIS, oversized)).isZero();
        assertThat(tokensIn(TOKEN_BUCKET_KEY)).isEqualTo(-50);

        // 토큰 1개가 차려면 (1 − (−50)) × 128 = 6528ms — 빚진 만큼 더 기다린다.
        assertThat(acquire(START_MILLIS, 1)).isEqualTo(51 * TOKEN_REFILL_MILLIS);
        // 가득 차지 않은 버킷에는 큰 요청이 다시 들어올 수 없다 — 다시 가득 차는 (100 + 50) × 128 = 19200ms.
        assertThat(acquire(START_MILLIS, oversized)).isEqualTo(150 * TOKEN_REFILL_MILLIS);
        assertThat(acquire(START_MILLIS + 150 * TOKEN_REFILL_MILLIS, oversized)).isZero();
    }

    @Test
    void 빚진_버킷은_다_갚을_때까지_살도록_TTL_을_늘리고_빚이_없는_버킷은_최소_TTL_을_넘지_않는다() {
        int oversized = (int) TOKEN_CAPACITY + 100;

        assertThat(acquire(START_MILLIS, oversized)).isZero();
        assertThat(tokensIn(TOKEN_BUCKET_KEY)).isEqualTo(-100);

        // 빚 100 을 갚고 가득 차기까지 (100 − (−100)) × 128 = 25600ms — 최소 TTL(20000ms)보다 길다.
        // 이보다 먼저 만료되면 다음 호출이 가득 찬 버킷을 새로 만들어 빚이 사라진다.
        long millisToFull = 200 * TOKEN_REFILL_MILLIS;
        assertThat(template.getExpire(TOKEN_BUCKET_KEY, TimeUnit.MILLISECONDS))
                .isGreaterThan(MINIMUM_TTL_MILLIS)
                .isBetween(millisToFull - 1_000, millisToFull);
        // 요청 버킷은 3건 중 1건만 썼으므로 다 차는 시간이 짧다 — 최소 TTL 그대로다.
        assertThat(template.getExpire(REQUEST_BUCKET_KEY, TimeUnit.MILLISECONDS))
                .isBetween(MINIMUM_TTL_MILLIS - 1_000, MINIMUM_TTL_MILLIS);
    }

    @Test
    void 요청_버킷과_토큰_버킷_중_더_긴_대기를_돌려준다() {
        drainRequestBucket(START_MILLIS);   // 요청 0건(대기 1024ms), 토큰 70개

        // 토큰 90개 필요 → 20개 부족 × 128 = 2560ms 가 요청 쪽 1024ms 보다 길다.
        assertThat(acquire(START_MILLIS, 90)).isEqualTo(20 * TOKEN_REFILL_MILLIS);
        // 토큰 10개면 토큰 쪽은 여유라 요청 쪽 1024ms 가 남는다.
        assertThat(acquire(START_MILLIS, 10)).isEqualTo(REQUEST_REFILL_MILLIS);
    }

    @Test
    void 통과하지_못해도_보충한_상태와_시각을_저장해_같은_시간을_두_번_보충하지_않는다() {
        drainRequestBucket(START_MILLIS);
        long halfWay = START_MILLIS + REQUEST_REFILL_MILLIS / 2;

        assertThat(acquire(halfWay, 10)).isEqualTo(REQUEST_REFILL_MILLIS / 2);

        // 거절됐지만 512ms 치 보충(0.5건)과 시각이 저장돼 있다.
        assertThat(template.opsForHash().get(REQUEST_BUCKET_KEY, "ts")).isEqualTo(String.valueOf(halfWay));
        assertThat(tokensIn(REQUEST_BUCKET_KEY)).isEqualTo(0.5);
        // 같은 시각에 다시 두드리면 지난 시간이 0 이라 같은 대기가 나온다 — 두 번 보충됐다면 통과했을 것이다.
        assertThat(acquire(halfWay, 10)).isEqualTo(REQUEST_REFILL_MILLIS / 2);
        assertThat(acquire(halfWay + REQUEST_REFILL_MILLIS / 2, 10)).isZero();
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
                String.valueOf(MINIMUM_TTL_MILLIS));
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

    /**
     * PATH 에 더해 homebrew·우분투 패키지의 기본 자리도 직접 본다 — Gradle 데몬처럼 PATH 가 깎인 프로세스에서도
     * 로컬에 설치된 redis-server 를 찾게 하기 위해서다.
     */
    private static Path findRedisServer() {
        List<String> directories = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            directories.addAll(List.of(path.split(File.pathSeparator)));
        }
        directories.addAll(List.of("/opt/homebrew/bin", "/usr/bin"));
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
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.ping();
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
