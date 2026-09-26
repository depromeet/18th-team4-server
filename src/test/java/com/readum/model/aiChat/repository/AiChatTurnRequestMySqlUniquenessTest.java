package com.readum.model.aiChat.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 식별자 유일성을 <b>운영 DB(MySQL·InnoDB)</b> 에 대고 확인하는 수동 실행 테스트.
 * 기본 빌드(./gradlew test)에서는 제외되며 {@code ./gradlew mysqlTest} 로만 돌린다 —
 * H2 는 MySQL 의 잠금·유일성 의미를 그대로 재현하지 않으므로, 경쟁 삽입은 실제 MySQL 로 확인한다.
 *
 * <p>사전 준비: 로컬 MySQL 컨테이너(readum-local-mysql, 127.0.0.1:3307) 기동.
 * 접속 정보는 환경 변수로 덮어쓸 수 있다 (MYSQL_TEST_HOST/PORT/DATABASE/USER/PASSWORD).
 * 이 테스트는 스키마 파일(V6)로 임시 테이블을 만들고, 끝나면 지운다 — 운영 테이블을 건드리지 않는다.
 */
@Tag("mysql")
class AiChatTurnRequestMySqlUniquenessTest {

    private static final String TABLE_NAME = "ai_chat_turn_request_uniqueness_probe";
    private static final Path MIGRATION_PATH =
            Path.of("src/main/resources/db/migration/V6__add_ai_chat_turn_request.sql");

    private static String jdbcUrl;
    private static String user;
    private static String password;

    @BeforeAll
    static void resolveConnectionSettings() {
        String host = envOrDefault("MYSQL_TEST_HOST", "127.0.0.1");
        String port = envOrDefault("MYSQL_TEST_PORT", "3307");
        String database = envOrDefault("MYSQL_TEST_DATABASE", "readum");
        jdbcUrl = "jdbc:mysql://%s:%s/%s".formatted(host, port, database);
        user = envOrDefault("MYSQL_TEST_USER", "readum");
        password = System.getenv("MYSQL_TEST_PASSWORD");
        if (password == null) {
            password = System.getenv("MYSQL_PASSWORD");
        }
        if (password == null) {
            throw new IllegalStateException(
                    "MySQL 비밀번호를 환경 변수 MYSQL_TEST_PASSWORD 또는 MYSQL_PASSWORD 로 넘겨 주세요.");
        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    /** 운영 스키마 파일(V6)의 정의를 그대로 쓰되 테이블 이름만 바꿔 임시 테이블로 만든다. */
    private static void createProbeTable() throws Exception {
        String createStatement = Files.readString(MIGRATION_PATH)
                .lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + "\n" + right)
                .replace("ai_chat_turn_request", TABLE_NAME)
                .replace("uk_ai_chat_turn_request_user_request", "uk_" + TABLE_NAME + "_user_request")
                .replace("idx_ai_chat_turn_request_recovery", "idx_" + TABLE_NAME + "_recovery")
                .trim();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            statement.execute(createStatement);
        }
    }

    private static void dropProbeTable() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
        }
    }

    private static int countRows(long userId, String requestId) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE user_id = ? AND request_id = ?")) {
            statement.setLong(1, userId);
            statement.setString(2, requestId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /** 요청 한 건의 자리 확보 삽입. 유일 위반이면 false. */
    private static boolean claim(long userId, String requestId) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO %s
                         (user_id, session_id, request_id, status, expires_at, created_at, updated_at)
                     VALUES (?, ?, ?, 'ACCEPTED', ?, ?, ?)
                     """.formatted(TABLE_NAME))) {
            statement.setLong(1, userId);
            statement.setLong(2, 7L);
            statement.setString(3, requestId);
            statement.setTimestamp(4, Timestamp.valueOf(now.plusMinutes(3)));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            statement.setTimestamp(6, Timestamp.valueOf(now));
            statement.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return false;
        }
    }

    @Test
    void 같은_식별자가_동시에_들어와도_MySQL_유일_제약이_정확히_한_건만_통과시킨다() throws Exception {
        createProbeTable();
        try {
            long userId = 1L;
            String requestId = "race-id";
            int concurrentRequestCount = 32;
            // 삽입을 최대한 같은 순간에 몰아 실제 경쟁을 만든다.
            CyclicBarrier startTogether = new CyclicBarrier(concurrentRequestCount);

            List<Boolean> claimed;
            try (ExecutorService executor = Executors.newFixedThreadPool(concurrentRequestCount)) {
                List<Callable<Boolean>> concurrentClaims = java.util.stream.IntStream
                        .range(0, concurrentRequestCount)
                        .<Callable<Boolean>>mapToObj(index -> () -> {
                            startTogether.await();
                            return claim(userId, requestId);
                        })
                        .toList();
                List<Future<Boolean>> results = executor.invokeAll(concurrentClaims);
                claimed = results.stream().map(future -> {
                    try {
                        return future.get();
                    } catch (Exception unexpected) {
                        throw new IllegalStateException(unexpected);
                    }
                }).toList();
            }

            assertThat(claimed.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
            assertThat(countRows(userId, requestId)).isEqualTo(1);
        } finally {
            dropProbeTable();
        }
    }

    @Test
    void 사용자가_다르면_같은_식별자도_각각_통과한다() throws Exception {
        createProbeTable();
        try {
            assertThat(claim(11L, "shared-id")).isTrue();
            assertThat(claim(12L, "shared-id")).isTrue();
        } finally {
            dropProbeTable();
        }
    }

    @Test
    void MySQL_기본_대조_규칙과_달리_대소문자가_다른_식별자는_다른_요청으로_본다() throws Exception {
        createProbeTable();
        try {
            // request_id 는 utf8mb4_bin 이라 대소문자를 구분한다 — 기본 대조 규칙이면 둘째가 중복으로 막힌다.
            assertThat(claim(13L, "Abc-Id")).isTrue();
            assertThat(claim(13L, "abc-id")).isTrue();
        } finally {
            dropProbeTable();
        }
    }
}
