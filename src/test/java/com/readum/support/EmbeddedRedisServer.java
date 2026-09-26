package com.readum.support;

import org.junit.jupiter.api.Assumptions;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 시험용 Redis 한 대를 루프백에 띄웠다 내린다.
 *
 * <p><b>왜 모의 Redis 를 쓰지 않는가.</b> 확인하려는 것이 Lua 스크립트 안에서 읽기·판정·쓰기가 한 번에
 * 끝나는지, 여럿이 동시에 두드려도 하나만 통과하는지, 키 수명이 제때 늘어나는지 같은 <b>Redis 자신의 성질</b>이다.
 * 흉내 낸 구현으로는 그 성질이 검증되지 않는다.
 *
 * <p>{@code redis-server} 바이너리를 찾지 못하면 그 테스트는 건너뛴다 — 없는 환경에서 빌드를 깨지 않되,
 * 건너뛴 사실은 실행 결과에 드러난다.
 */
public final class EmbeddedRedisServer implements AutoCloseable {

    private final Process process;
    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate template;

    private EmbeddedRedisServer(
            Process process, LettuceConnectionFactory connectionFactory, StringRedisTemplate template) {
        this.process = process;
        this.connectionFactory = connectionFactory;
        this.template = template;
    }

    /** 띄운다. {@code redis-server} 를 찾지 못하면 테스트를 건너뛴다. */
    public static EmbeddedRedisServer startOrSkip() throws Exception {
        Path redisServer = findRedisServer();
        Assumptions.assumeTrue(redisServer != null, "redis-server 를 찾지 못해 건너뜀");

        int port = freePort();
        Process process = new ProcessBuilder(
                redisServer.toString(), "--port", String.valueOf(port),
                "--bind", "127.0.0.1", "--save", "", "--appendonly", "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        awaitReady(process, connectionFactory);
        return new EmbeddedRedisServer(process, connectionFactory, template);
    }

    public StringRedisTemplate template() {
        return template;
    }

    public org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    /** 남은 키를 지운다 — 테스트 사이의 상태가 새어 나가지 않게. */
    public void deleteKeys(String pattern) {
        java.util.Set<String> leftovers = template.keys(pattern);
        if (leftovers != null && !leftovers.isEmpty()) {
            template.delete(leftovers);
        }
    }

    @Override
    public void close() {
        connectionFactory.destroy();
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
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

    private static void awaitReady(Process process, LettuceConnectionFactory connectionFactory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "redis-server 가 기동 중 종료됐습니다 (exit " + process.exitValue() + ")");
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
