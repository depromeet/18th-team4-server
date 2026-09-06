package com.readum.model.aiChat.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * 요청 종료의 <b>행 잠금</b> 의미를 운영 DB(MySQL·InnoDB)에 대고 확인하는 수동 실행 테스트.
 * 기본 빌드(./gradlew test)에서는 제외되며 {@code ./gradlew mysqlTest} 로만 돌린다 —
 * H2 는 MySQL 의 잠금·격리 의미를 그대로 재현하지 않는다.
 *
 * <p>확인하려는 것: 늦게 도착한 성공과 만료 복구가 같은 요청 행을 <b>동시에</b> 끝내려 할 때,
 * 잠금 → 미종료 확인 → 상태 전이 순서를 지키면 정확히 한쪽만 반영된다. 두 번째 트랜잭션은 첫 번째가
 * 커밋될 때까지 잠금에서 기다렸다가, 커밋된 최신 상태(이미 종료)를 보고 아무것도 하지 않는다.
 * 대조로, 잠금 없이 읽으면 REPEATABLE READ 의 일관 읽기 때문에 둘 다 미종료로 읽어 양쪽이 반영된다 —
 * 예약 반환이 두 번 일어나는 상황이다.
 *
 * <p>사전 준비: 로컬 MySQL 컨테이너(readum-local-mysql, 127.0.0.1:3307) 기동.
 * 접속 정보는 환경 변수로 덮어쓸 수 있다 (MYSQL_TEST_HOST/PORT/DATABASE/USER/PASSWORD).
 * 이 테스트는 스키마 파일(V6)로 임시 테이블을 만들고, 끝나면 지운다 — 운영 테이블을 건드리지 않는다.
 */
@Tag("mysql")
class AiChatTurnOutcomeMySqlRowLockTest {

    private static final String TABLE_NAME = "ai_chat_turn_request_rowlock_probe";
    private static final Path MIGRATION_PATH =
            Path.of("src/main/resources/db/migration/V6__add_ai_chat_turn_request.sql");
    private static final int RESERVED_TOKENS = 300;

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

    /** 예약까지 마친(RESERVED) 미종료 요청 한 건을 만든다. */
    private static long insertReservedTurnRequest(long userId, String requestId) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO %s
                         (user_id, session_id, request_id, status, budget_period_key, reserved_tokens,
                          expires_at, created_at, updated_at)
                     VALUES (?, 7, ?, 'RESERVED', 20260906, ?, ?, ?, ?)
                     """.formatted(TABLE_NAME), Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setString(2, requestId);
            statement.setInt(3, RESERVED_TOKENS);
            statement.setTimestamp(4, Timestamp.valueOf(now.plusMinutes(3)));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            statement.setTimestamp(6, Timestamp.valueOf(now));
            statement.executeUpdate();
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                generatedKeys.next();
                return generatedKeys.getLong(1);
            }
        }
    }

    private static String statusOf(long turnRequestId) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM " + TABLE_NAME + " WHERE id = ?")) {
            statement.setLong(1, turnRequestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    /**
     * 종료 트랜잭션 한 번을 흉내 낸다: (잠그고) 상태를 읽어 미종료면 종료 상태로 바꾼다.
     *
     * @param withRowLock false 면 잠금 없이 읽는다 — 대조군
     * @return 이번 호출이 종료를 확정했으면 true
     */
    private static boolean finishTurn(long turnRequestId, String terminalStatus, boolean withRowLock)
            throws Exception {
        String selectStatement = "SELECT status FROM " + TABLE_NAME + " WHERE id = ?"
                + (withRowLock ? " FOR UPDATE" : "");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                String currentStatus;
                try (PreparedStatement statement = connection.prepareStatement(selectStatement)) {
                    statement.setLong(1, turnRequestId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        currentStatus = resultSet.getString(1);
                    }
                }
                if (isTerminal(currentStatus)) {
                    connection.commit();
                    return false;
                }
                // 확인과 전이 사이의 틈을 넓혀, 잠금이 없으면 실제로 둘 다 통과한다는 것을 드러낸다.
                Thread.sleep(50);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + TABLE_NAME + " SET status = ?, updated_at = ? WHERE id = ?")) {
                    statement.setString(1, terminalStatus);
                    statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                    statement.setLong(3, turnRequestId);
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static boolean isTerminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "EXPIRED").contains(status);
    }

    private static List<Boolean> runTogether(List<Callable<Boolean>> attempts) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts.size())) {
            List<Future<Boolean>> results = executor.invokeAll(attempts);
            return results.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception unexpected) {
                    throw new IllegalStateException(unexpected);
                }
            }).toList();
        }
    }

    @Test
    void 같은_요청을_동시에_끝내려_해도_행_잠금이_한쪽만_반영시킨다() throws Exception {
        createProbeTable();
        try {
            long turnRequestId = insertReservedTurnRequest(1L, "row-lock-race");
            CyclicBarrier startTogether = new CyclicBarrier(2);

            List<Boolean> finalized = runTogether(List.of(
                    () -> {
                        startTogether.await();
                        return finishTurn(turnRequestId, "SUCCEEDED", true);
                    },
                    () -> {
                        startTogether.await();
                        return finishTurn(turnRequestId, "EXPIRED", true);
                    }));

            // 둘 중 어느 쪽이 이기는지는 정하지 않는다 — 한쪽만 반영되는 것이 계약이다.
            assertThat(finalized.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
            assertThat(statusOf(turnRequestId)).isIn("SUCCEEDED", "EXPIRED");
        } finally {
            dropProbeTable();
        }
    }

    @Test
    void 만료_복구가_동시에_두_번_돌아도_예약_반환은_한_번만_반영된다() throws Exception {
        createProbeTable();
        try {
            long turnRequestId = insertReservedTurnRequest(3L, "double-recovery-race");
            CyclicBarrier startTogether = new CyclicBarrier(2);

            // 스캔이 겹치거나(한 프로세스) 여러 대가 같은 주기로 돌면 같은 행을 동시에 집을 수 있다.
            // 복구에는 선점 표식이 없고, 겹침을 막는 것은 이 행 잠금과 미종료 확인뿐이다.
            List<Boolean> finalized = runTogether(List.of(
                    () -> {
                        startTogether.await();
                        return finishTurn(turnRequestId, "EXPIRED", true);
                    },
                    () -> {
                        startTogether.await();
                        return finishTurn(turnRequestId, "EXPIRED", true);
                    }));

            // 한쪽만 확정한다 — 둘 다 확정하면 예약을 두 번 되돌려 원장이 음수가 된다.
            assertThat(finalized.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
            assertThat(statusOf(turnRequestId)).isEqualTo("EXPIRED");
        } finally {
            dropProbeTable();
        }
    }

    @Test
    void 잠금_없이_읽으면_둘_다_미종료로_보고_양쪽이_반영된다() throws Exception {
        createProbeTable();
        try {
            long turnRequestId = insertReservedTurnRequest(2L, "no-lock-race");
            CyclicBarrier startTogether = new CyclicBarrier(2);

            List<Boolean> finalized = runTogether(List.of(
                    () -> {
                        startTogether.await();
                        return finishTurn(turnRequestId, "SUCCEEDED", false);
                    },
                    () -> {
                        startTogether.await();
                        return finishTurn(turnRequestId, "EXPIRED", false);
                    }));

            // 대조군 — 이 상태가 실제 코드에서 일어나면 저장·정산·예약 반환이 두 번 반영된다.
            assertThat(finalized.stream().filter(Boolean::booleanValue).count()).isEqualTo(2);
        } finally {
            dropProbeTable();
        }
    }
}
