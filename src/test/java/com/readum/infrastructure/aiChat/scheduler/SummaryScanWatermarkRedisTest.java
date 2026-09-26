package com.readum.infrastructure.aiChat.scheduler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자동 적재 스캔 기준점의 계약을 <b>실제 Redis</b> 위에서 검증한다.
 * 이 표식이 틀리면 건너뛴 회차의 대상이 조용히 사라지므로, 심는 시점과 전진 방향이 핵심이다.
 */
class SummaryScanWatermarkRedisTest {

    private static final String KEY = "ai:summary:last-scan-at";

    private static Process redisProcess;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    private SummaryScanWatermark watermark;

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
        Runtime.getRuntime().addShutdownHook(new Thread(redisProcess::destroy));

        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        awaitReady();
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
    void freshWatermark() {
        template.delete(KEY);
        watermark = new SummaryScanWatermark(template);
    }

    @Test
    void 첫_회차가_거절돼도_기준점은_남아_다음_회차가_그_지점부터_훑는다() {
        // 이것이 이 표식의 존재 이유다. 기준점을 성공한 뒤에만 심으면, 첫 회차가 장애로 거절될 때
        // 표식 없이 끝나고 다음 회차가 다시 "최근 24시간" 으로 좁혀 그 사이 구간을 통째로 잃는다.
        LocalDateTime firstAttempt = LocalDateTime.of(2026, 9, 12, 6, 0);
        LocalDateTime baseline = firstAttempt.minusHours(24);

        LocalDateTime afterRejectedRun = watermark.initializeOrRead(baseline);

        assertThat(afterRejectedRun).isEqualTo(baseline);

        // 다음 날 회차 — 기준점을 다시 읽으면 어제의 기준점이 그대로 나온다(24시간으로 좁혀지지 않는다).
        LocalDateTime nextDay = firstAttempt.plusDays(1);
        assertThat(watermark.initializeOrRead(nextDay.minusHours(24)))
                .as("이미 심긴 기준점이 정본이다")
                .isEqualTo(baseline);
    }

    @Test
    void 성공한_회차만_기준점을_전진시킨다() {
        LocalDateTime baseline = LocalDateTime.of(2026, 9, 12, 6, 0).minusHours(24);
        watermark.initializeOrRead(baseline);
        LocalDateTime scannedAt = LocalDateTime.of(2026, 9, 12, 6, 0);

        watermark.advanceTo(scannedAt);

        assertThat(watermark.initializeOrRead(LocalDateTime.of(2026, 9, 13, 6, 0).minusHours(24)))
                .isEqualTo(scannedAt);
    }

    @Test
    void 기준점은_뒤로_가지_않는다() {
        // 여러 서버가 겹쳐 돌 때 늦게 끝난 쪽이 앞선 표식을 되돌리면 같은 구간을 다시 훑는다.
        LocalDateTime later = LocalDateTime.of(2026, 9, 12, 6, 0);
        LocalDateTime earlier = later.minusHours(3);
        watermark.advanceTo(later);

        watermark.advanceTo(earlier);

        assertThat(watermark.initializeOrRead(earlier)).isEqualTo(later);
    }

    @Test
    void 표식을_다루지_못하면_기본값으로_되돌리지_않고_알린다() throws Exception {
        // 조용히 "최근 24시간" 으로 좁히면 건너뛴 구간을 잃고도 정상처럼 보인다.
        // 공유 연결을 망가뜨리면 뒤따르는 테스트까지 말려들므로, 이 테스트만의 죽은 연결을 따로 만든다.
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", freePort()),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        deadFactory.afterPropertiesSet();
        try {
            SummaryScanWatermark unreachable =
                    new SummaryScanWatermark(new StringRedisTemplate(deadFactory));

            assertThatThrownBy(() -> unreachable.initializeOrRead(LocalDateTime.now().minusHours(24)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            deadFactory.destroy();
        }
    }

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
                throw new IllegalStateException(
                        "redis-server 가 기동 중 종료됐습니다 (exit " + redisProcess.exitValue() + ")");
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
}
