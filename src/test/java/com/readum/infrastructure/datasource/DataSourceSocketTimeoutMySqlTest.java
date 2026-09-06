package com.readum.infrastructure.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 프로파일의 DataSource 설정이 실제로 <b>연결의 소켓 읽기 기한</b>이 되는지 확인하는 수동 실행 테스트.
 * 기본 빌드(./gradlew test)에서는 제외되며 {@code ./gradlew mysqlTest} 로만 돌린다 —
 * 기본 테스트가 쓰는 H2 는 이 설정을 아예 거부하므로(모르는 연결 설정) 실제 MySQL 로만 확인할 수 있다.
 *
 * <p>확인하는 계약: 프로파일 yml 의 JDBC URL 에 적은 {@code socketTimeout} 이
 * <b>풀에서 빌린 연결</b>의 {@link Connection#getNetworkTimeout()} 으로 나타난다.
 * Connector/J 가 {@code socketTimeout} 을 그 값으로 노출하고, HikariCP 는 연결을 만들 때 읽어 둔
 * 원래 값을 빌려줄 때 그대로 돌려놓기 때문이다. 이 단언이 깨지면 설정을 적어 두기만 하고
 * 실제로는 기한 없이 읽고 있다는 뜻이다.
 *
 * <p>URL 의 <b>질의 문자열</b>(설정이 담긴 부분)은 {@code application-local.yml} 에서 그대로 읽어 온다 —
 * 값을 여기 베껴 두면 프로파일만 바뀌었을 때 테스트가 옛 값을 계속 통과시킨다.
 * 접속 대상(host/port/database)은 로컬 테스트 컨테이너를 가리키게 환경 변수로 정한다
 * (프로파일의 기본 포트 3306 은 앱용이고, 테스트 컨테이너 readum-local-mysql 은 3307 이다).
 *
 * <p>사전 준비: 로컬 MySQL 컨테이너(readum-local-mysql, 127.0.0.1:3307) 기동.
 * 접속 정보는 환경 변수로 덮어쓸 수 있다 (MYSQL_TEST_HOST/PORT/DATABASE/USER/PASSWORD).
 */
@Tag("mysql")
class DataSourceSocketTimeoutMySqlTest {

    private static final Path LOCAL_PROFILE_PATH = Path.of("src/main/resources/application-local.yml");
    private static final Pattern DATASOURCE_URL_LINE = Pattern.compile("^\\s*url:\\s*(jdbc:mysql://\\S+)\\s*$");

    /** 기대 기한 60초 — MySQL 잠금 대기 기한(기본 50초)보다 길게 둔 값. 근거는 docs/ops/ai-chat-shutdown-and-recovery.md */
    private static final int EXPECTED_NETWORK_TIMEOUT_MILLIS = 60_000;

    @Test
    void 풀에서_빌린_연결은_프로파일에_적은_소켓_읽기_기한_60초를_그대로_갖는다() throws Exception {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(localProfileJdbcUrlAgainstTestDatabase());
        poolConfig.setUsername(envOrDefault("MYSQL_TEST_USER", "readum"));
        poolConfig.setPassword(requiredPassword());
        poolConfig.setMaximumPoolSize(1);
        poolConfig.setPoolName("socket-timeout-probe");

        try (HikariDataSource dataSource = new HikariDataSource(poolConfig);
             Connection connection = dataSource.getConnection()) {
            assertThat(connection.getNetworkTimeout()).isEqualTo(EXPECTED_NETWORK_TIMEOUT_MILLIS);
        }
    }

    /** local 프로파일 URL 의 질의 문자열은 그대로 쓰고, 접속 대상만 테스트 컨테이너로 바꾼다. */
    private static String localProfileJdbcUrlAgainstTestDatabase() throws Exception {
        String profileUrl = readProfileDataSourceUrl();
        int queryStart = profileUrl.indexOf('?');
        assertThat(queryStart)
                .as("local 프로파일 JDBC URL 에 연결 설정(질의 문자열)이 있어야 한다: %s", profileUrl)
                .isGreaterThan(0);
        String connectionSettings = profileUrl.substring(queryStart + 1);
        assertThat(connectionSettings)
                .as("local 프로파일 JDBC URL 에 socketTimeout 이 있어야 한다")
                .contains("socketTimeout=" + EXPECTED_NETWORK_TIMEOUT_MILLIS);

        return "jdbc:mysql://%s:%s/%s?%s".formatted(
                envOrDefault("MYSQL_TEST_HOST", "127.0.0.1"),
                envOrDefault("MYSQL_TEST_PORT", "3307"),
                envOrDefault("MYSQL_TEST_DATABASE", "readum"),
                connectionSettings);
    }

    private static String readProfileDataSourceUrl() throws Exception {
        List<String> lines = Files.readAllLines(LOCAL_PROFILE_PATH);
        for (String line : lines) {
            Matcher matcher = DATASOURCE_URL_LINE.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException(LOCAL_PROFILE_PATH + " 에서 DataSource JDBC URL 을 찾지 못했습니다.");
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
