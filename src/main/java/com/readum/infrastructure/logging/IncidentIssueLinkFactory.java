package com.readum.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;

/**
 * ERROR 로그 한 건을 GitHub "분석 이슈 생성" 프리필 링크로 변환한다.
 *
 * <p>링크를 누르면 이슈 생성 화면이 제목/본문/{@code error} 라벨이 채워진 상태로 열리고,
 * 사람이 생성 버튼을 누르는 순간(=분석 승인) {@code incident-analysis.yml} 워크플로우가 발동한다.
 * 본문 끝의 {@code <!-- deploy-sha: ... -->} 주석은 워크플로우가 분석 대상 커밋을
 * checkout 할 때 파싱하는 마커다.
 *
 * <p>GitHub 프리필 URL 은 길이 한계가 있어(넉넉히 8KB 안팎에서 거부) stacktrace 를 절단해 싣고,
 * 한글 URL 인코딩 팽창까지 감안해 최종 URL 이 상한을 넘으면 stacktrace 를 더 줄여 다시 만든다.
 */
final class IncidentIssueLinkFactory {

    static final String UNKNOWN_DEPLOY_COMMIT = "unknown";

    private static final int MAX_TITLE_CHARS = 80;
    private static final int MAX_STACK_TRACE_CHARS = 1000;
    private static final int MIN_STACK_TRACE_CHARS = 200;
    private static final int MAX_URL_CHARS = 6500;

    private final String repositoryUrl;
    private final String environment;
    private final String deployCommit;

    IncidentIssueLinkFactory(String repositoryUrl, String environment, String deployCommit) {
        this.repositoryUrl = stripTrailingSlash(repositoryUrl);
        this.environment = environment;
        this.deployCommit = deployCommit;
    }

    /**
     * 빌드 시 gradle-git-properties 플러그인이 jar 에 넣어 준 git.properties 에서
     * 배포 커밋(짧은 SHA)을 읽는다. 파일이 없으면(테스트, .git 없는 빌드) {@value #UNKNOWN_DEPLOY_COMMIT}.
     */
    static String readDeployCommitFromClasspath() {
        try (InputStream propertiesStream =
                     IncidentIssueLinkFactory.class.getResourceAsStream("/git.properties")) {
            if (propertiesStream == null) {
                return UNKNOWN_DEPLOY_COMMIT;
            }
            Properties gitProperties = new Properties();
            gitProperties.load(propertiesStream);
            return gitProperties.getProperty("git.commit.id.abbrev", UNKNOWN_DEPLOY_COMMIT);
        } catch (IOException e) {
            return UNKNOWN_DEPLOY_COMMIT;
        }
    }

    String create(ILoggingEvent event, String fingerprint) {
        String title = truncate("[장애] " + fingerprint, MAX_TITLE_CHARS);
        String maskedMessage = SensitiveDataMasker.mask(event.getFormattedMessage());
        String maskedStackTrace = maskedStackTrace(event);

        String stackTrace = truncate(maskedStackTrace, MAX_STACK_TRACE_CHARS);
        String url = buildUrl(title, buildBody(event, fingerprint, maskedMessage, stackTrace));
        while (url.length() > MAX_URL_CHARS && stackTrace.length() > MIN_STACK_TRACE_CHARS) {
            stackTrace = truncate(stackTrace, stackTrace.length() / 2);
            url = buildUrl(title, buildBody(event, fingerprint, maskedMessage, stackTrace));
        }
        return url;
    }

    private String maskedStackTrace(ILoggingEvent event) {
        if (event.getThrowableProxy() == null) {
            return "(예외 없음)";
        }
        return SensitiveDataMasker.mask(ThrowableProxyUtil.asString(event.getThrowableProxy()));
    }

    private String buildBody(ILoggingEvent event, String fingerprint, String maskedMessage, String stackTrace) {
        String traceId = event.getMDCPropertyMap().getOrDefault("traceId", "-");
        return """
                ## 장애 정보
                - 발생 시각: %s
                - 환경: `%s`
                - 배포 커밋: `%s`
                - fingerprint: `%s`
                - traceId: `%s`
                - logger: `%s`

                ## 메시지
                ```
                %s
                ```

                ## Stacktrace
                ```
                %s
                ```

                <!-- deploy-sha: %s -->
                """.formatted(
                Instant.ofEpochMilli(event.getTimeStamp()),
                environment,
                deployCommit,
                fingerprint,
                traceId,
                event.getLoggerName(),
                maskedMessage,
                stackTrace,
                deployCommit
        );
    }

    private String buildUrl(String title, String body) {
        return repositoryUrl + "/issues/new"
                + "?labels=error"
                + "&title=" + encode(title)
                + "&body=" + encode(body);
    }

    private static String encode(String value) {
        // URLEncoder 는 공백을 '+' 로 바꾸는데, GitHub 프리필은 '%20' 이 안전하다.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
