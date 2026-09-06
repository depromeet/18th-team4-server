package com.readum.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import lombok.Setter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ERROR 이상 로그를 Slack Incoming Webhook 으로 전송하는 Logback Appender.
 *
 * <p>주의: 이 Appender 자체는 동기적으로 동작하지 않도록 logback-spring.xml 에서 반드시
 * {@code AsyncAppender} 로 감싸 사용한다. Slack 전송 실패가 애플리케이션 요청 처리를 막으면 안 된다.
 *
 * <p>Slack 으로 보내기 전 같은 오류(logger + level + 예외 클래스 + 메시지)는 일정 시간 동안
 * 중복 전송을 억제하고, 토큰/Authorization 등 민감 문자열은 마스킹한다.
 *
 * <p>메시지에는 오류 식별용 fingerprint({@link IncidentFingerprint})와, 사람이 분석을 승인할 때
 * 누르는 "분석 이슈 만들기" 버튼(Block Kit)이 함께 실린다.
 */
@Setter
public class SlackWebhookAppender extends AppenderBase<ILoggingEvent> {

    private static final String UNKNOWN_DEPLOY_COMMIT = "unknown";

    // 버튼 value 는 Slack Block Kit 제한(최대 2000자)을 넘으면 메시지 전송 자체가 거부된다.
    // message·stacktrace 는 value 용으로 짧게 자른다(사람이 보는 표시 텍스트는 전체 노출).
    private static final int MAX_VALUE_MESSAGE_CHARS = 300;
    private static final int MAX_VALUE_STACKTRACE_CHARS = 1000;

    private String webhookUrl;
    private String appName = "readum";
    private String env = "unknown";
    private long duplicateSuppressMillis = 60_000;
    private int maxStackTraceChars = 1800;

    private String deployCommit = UNKNOWN_DEPLOY_COMMIT;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ConcurrentMap<String, Long> lastSentAt = new ConcurrentHashMap<>();

    @Override
    public void start() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            addWarn("Slack webhookUrl is empty. SlackWebhookAppender will not start.");
            return;
        }
        deployCommit = readDeployCommitFromClasspath();
        super.start();
    }

    private static String readDeployCommitFromClasspath() {
        try (InputStream in = SlackWebhookAppender.class.getResourceAsStream("/git.properties")) {
            if (in == null) {
                return UNKNOWN_DEPLOY_COMMIT;
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("git.commit.id.abbrev", UNKNOWN_DEPLOY_COMMIT);
        } catch (IOException e) {
            return UNKNOWN_DEPLOY_COMMIT;
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted()) {
            return;
        }

        try {
            String dedupKey = buildDedupKey(event);
            long now = System.currentTimeMillis();
            Long previous = lastSentAt.get(dedupKey);

            if (previous != null && now - previous < duplicateSuppressMillis) {
                return;
            }

            lastSentAt.put(dedupKey, now);

            String payload = buildSlackPayload(event);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        addWarn("Failed to send Slack alert: " + ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            addWarn("SlackWebhookAppender append failed: " + e.getMessage());
        }
    }

    private String buildDedupKey(ILoggingEvent event) {
        String throwableClass = event.getThrowableProxy() == null
                ? ""
                : event.getThrowableProxy().getClassName();

        return event.getLoggerName()
                + "|" + event.getLevel()
                + "|" + throwableClass
                + "|" + event.getFormattedMessage();
    }

    private String buildSlackPayload(ILoggingEvent event) {
        String traceId = event.getMDCPropertyMap().getOrDefault("traceId", "-");
        String spanId = event.getMDCPropertyMap().getOrDefault("spanId", "-");
        String fingerprint = IncidentFingerprint.of(event);

        String stackTrace = "";
        if (event.getThrowableProxy() != null) {
            stackTrace = ThrowableProxyUtil.asString(event.getThrowableProxy());
            if (stackTrace.length() > maxStackTraceChars) {
                stackTrace = stackTrace.substring(0, maxStackTraceChars) + "\n... truncated";
            }
        }

        // 마스킹된 값을 한 번만 만들어 표시 텍스트와 버튼 value 에 함께 쓴다.
        String maskedAppName = SensitiveDataMasker.mask(appName);
        String maskedEnv = SensitiveDataMasker.mask(env);
        String maskedLogger = SensitiveDataMasker.mask(event.getLoggerName());
        String maskedFingerprint = SensitiveDataMasker.mask(fingerprint);
        String maskedDeploy = SensitiveDataMasker.mask(deployCommit);
        String maskedTraceId = SensitiveDataMasker.mask(traceId);
        String maskedSpanId = SensitiveDataMasker.mask(spanId);
        String maskedMessage = SensitiveDataMasker.mask(event.getFormattedMessage());
        String maskedStackTrace = SensitiveDataMasker.mask(stackTrace);

        // 표시 텍스트: 사람이 읽는 알림 본문. incident-analysis.yml 의 스레드 탐색이
        // "fingerprint 포함 + ERROR 발생" 으로 원본 알림을 찾으므로 이 두 요소는 유지한다.
        String text = """
                :rotating_light: *%s ERROR 발생*
                *env*: `%s`
                *logger*: `%s`
                *fingerprint*: `%s`
                *deploy*: `%s`
                *traceId*: `%s`
                *spanId*: `%s`
                *time*: `%s`

                *message*
                ```%s```

                *stacktrace*
                ```%s```
                """.formatted(
                maskedAppName,
                maskedEnv,
                maskedLogger,
                maskedFingerprint,
                maskedDeploy,
                maskedTraceId,
                maskedSpanId,
                Instant.ofEpochMilli(event.getTimeStamp()),
                maskedMessage,
                maskedStackTrace
        );

        // 버튼 value = 수신기가 그대로 읽는 구조화 JSON. 수신기가 본문(text)을 regex 로 긁던 방식은
        // 실제 Slack 상호작용 payload 의 message.text 에 의존해 취약했다 — value 는 클릭된 버튼에 항상 실려 안전하다.
        String buttonValue = "{"
                + "\"fingerprint\":\"" + escapeJson(maskedFingerprint) + "\","
                + "\"deploy\":\"" + escapeJson(maskedDeploy) + "\","
                + "\"traceId\":\"" + escapeJson(maskedTraceId) + "\","
                + "\"message\":\"" + escapeJson(truncate(maskedMessage, MAX_VALUE_MESSAGE_CHARS)) + "\","
                + "\"stacktrace\":\"" + escapeJson(truncate(maskedStackTrace, MAX_VALUE_STACKTRACE_CHARS)) + "\""
                + "}";

        String escapedText = escapeJson(text);
        // blocks: 섹션(표시) + 버튼. 필요한 필드는 버튼 value 로 전달하므로 섹션·text 는 표시 전용이다.
        return "{\"text\":\"" + escapedText + "\","
                + "\"blocks\":["
                + "{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"" + escapedText + "\"}},"
                + "{\"type\":\"actions\",\"elements\":["
                + "{\"type\":\"button\",\"style\":\"primary\","
                + "\"text\":{\"type\":\"plain_text\",\"text\":\"분석 이슈 만들기\"},"
                + "\"action_id\":\"create_incident_issue\","
                + "\"value\":\"" + escapeJson(buttonValue) + "\"}"
                + "]}"
                + "]}";
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…";
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
