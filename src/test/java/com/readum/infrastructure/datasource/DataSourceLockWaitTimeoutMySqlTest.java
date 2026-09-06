package com.readum.infrastructure.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 프로파일의 Hikari 연결 초기화 SQL 이 실제로 <b>연결 세션의 행 잠금 대기 기한</b>이 되는지 확인하는
 * 수동 실행 테스트. 기본 빌드(./gradlew test)에서는 제외되며 {@code ./gradlew mysqlTest} 로만 돌린다 —
 * 기본 테스트가 쓰는 H2 는 이 구문을 아예 거부하므로(2.4.240 에서
 * {@code Syntax error in SQL statement "SET SESSION [*]innodb_lock_wait_timeout = 5"} 확인)
 * 실제 MySQL 로만 확인할 수 있다.
 *
 * <p>확인하는 계약: 프로파일 yml 의 {@code spring.datasource.hikari.connection-init-sql} 이
 * <b>풀에서 빌린 연결</b>의 {@code @@innodb_lock_wait_timeout} 으로 나타난다. HikariCP 는 연결을 만들 때
 * 이 문장을 한 번 실행하고, {@code SET SESSION} 은 그 연결이 살아 있는 동안 유지된다.
 * 이 단언이 깨지면 설정을 적어 두기만 하고 실제로는 MySQL 기본 50초를 쓰고 있다는 뜻이다.
 *
 * <p>초기화 SQL 은 {@code application-local.yml} 에서 그대로 읽어 온다 — 값을 여기 베껴 두면
 * 프로파일만 바뀌었을 때 테스트가 옛 값을 계속 통과시킨다. 접속 대상(host/port/database)은 로컬 테스트
 * 컨테이너를 가리키게 환경 변수로 정한다(readum-local-mysql, 127.0.0.1:3307).
 *
 * <p>사전 준비와 실행은 {@link DataSourceSocketTimeoutMySqlTest} 와 같다.
 */
@Tag("mysql")
class DataSourceLockWaitTimeoutMySqlTest {

    private static final Path LOCAL_PROFILE_PATH = Path.of("src/main/resources/application-local.yml");
    private static final Pattern DATASOURCE_URL_LINE = Pattern.compile("^\\s*url:\\s*(jdbc:mysql://\\S+)\\s*$");
    private static final Pattern CONNECTION_INIT_SQL_LINE =
            Pattern.compile("^\\s*connection-init-sql:\\s*(.+?)\\s*$");

    /** 기대 기한 5초 — 소켓 읽기 기한 60초보다 짧아, 잠금 대기는 항상 DB 쪽에서 먼저 끝난다. */
    private static final int EXPECTED_LOCK_WAIT_TIMEOUT_SECONDS = 5;

    @Test
    void 풀에서_빌린_연결은_프로파일에_적은_행_잠금_대기_기한_5초를_그대로_갖는다() throws Exception {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(localProfileJdbcUrlAgainstTestDatabase());
        poolConfig.setUsername(envOrDefault("MYSQL_TEST_USER", "readum"));
        poolConfig.setPassword(requiredPassword());
        poolConfig.setConnectionInitSql(localProfileConnectionInitSql());
        poolConfig.setMaximumPoolSize(1);
        poolConfig.setPoolName("lock-wait-timeout-probe");

        try (HikariDataSource dataSource = new HikariDataSource(poolConfig);
             Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet lockWaitTimeout = statement.executeQuery("SELECT @@innodb_lock_wait_timeout")) {
            assertThat(lockWaitTimeout.next()).isTrue();
            assertThat(lockWaitTimeout.getInt(1)).isEqualTo(EXPECTED_LOCK_WAIT_TIMEOUT_SECONDS);
        }
    }

    /** local 프로파일 URL 의 질의 문자열은 그대로 쓰고, 접속 대상만 테스트 컨테이너로 바꾼다. */
    private static String localProfileJdbcUrlAgainstTestDatabase() throws Exception {
        String profileUrl = readProfileLine(DATASOURCE_URL_LINE, "DataSource JDBC URL");
        int queryStart = profileUrl.indexOf('?');
        assertThat(queryStart)
                .as("local 프로파일 JDBC URL 에 연결 설정(질의 문자열)이 있어야 한다: %s", profileUrl)
                .isGreaterThan(0);

        return "jdbc:mysql://%s:%s/%s?%s".formatted(
                envOrDefault("MYSQL_TEST_HOST", "127.0.0.1"),
                envOrDefault("MYSQL_TEST_PORT", "3307"),
                envOrDefault("MYSQL_TEST_DATABASE", "readum"),
                profileUrl.substring(queryStart + 1));
    }

    private static String localProfileConnectionInitSql() throws Exception {
        String initSql = readProfileLine(CONNECTION_INIT_SQL_LINE, "Hikari connection-init-sql");
        assertThat(initSql)
                .as("local 프로파일의 연결 초기화 SQL 이 행 잠금 대기 기한을 %d초로 걸어야 한다",
                        EXPECTED_LOCK_WAIT_TIMEOUT_SECONDS)
                .contains("innodb_lock_wait_timeout")
                .contains(String.valueOf(EXPECTED_LOCK_WAIT_TIMEOUT_SECONDS));
        return initSql;
    }

    private static String readProfileLine(Pattern pattern, String what) throws Exception {
        List<String> lines = Files.readAllLines(LOCAL_PROFILE_PATH);
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException(LOCAL_PROFILE_PATH + " 에서 " + what + " 을 찾지 못했습니다.");
    }

    private static String requiredPassword() {
        String password = System.getenv("MYSQL_TEST_PASSWORD");
        if (password == null) {
            password = System.getenv("MYSQL_PASSWORD");
        }
        if (password == null) {
            throw new IllegalStateException(
                    "MySQL 비밀번호를 환경 변수 MYSQL_TEST_PASSWORD 또는 MYSQL_PASSWORD 로 넘겨 주세요.");
        }
        return password;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
