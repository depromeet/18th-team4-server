package com.readum.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

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
 */
public class SlackWebhookAppender extends AppenderBase<ILoggingEvent> {

    private String webhookUrl;
    private String appName = "readum";
    private String env = "unknown";
    private long duplicateSuppressMillis = 60_000;
    private int maxStackTraceChars = 1800;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ConcurrentMap<String, Long> lastSentAt = new ConcurrentHashMap<>();

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public void setDuplicateSuppressMillis(long duplicateSuppressMillis) {
        this.duplicateSuppressMillis = duplicateSuppressMillis;
    }

    public void setMaxStackTraceChars(int maxStackTraceChars) {
        this.maxStackTraceChars = maxStackTraceChars;
    }

    @Override
    public void start() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            addWarn("Slack webhookUrl is empty. SlackWebhookAppender will not start.");
            return;
        }
        super.start();
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

        String stackTrace = "";
        if (event.getThrowableProxy() != null) {
            stackTrace = ThrowableProxyUtil.asString(event.getThrowableProxy());
            if (stackTrace.length() > maxStackTraceChars) {
                stackTrace = stackTrace.substring(0, maxStackTraceChars) + "\n... truncated";
            }
        }

        String text = """
                :rotating_light: *%s ERROR 발생*
                *env*: `%s`
                *logger*: `%s`
                *traceId*: `%s`
                *spanId*: `%s`
                *time*: `%s`

                *message*
                ```%s```

                *stacktrace*
                ```%s```
                """.formatted(
                escape(appName),
                escape(env),
                escape(event.getLoggerName()),
                escape(traceId),
                escape(spanId),
                Instant.ofEpochMilli(event.getTimeStamp()),
                escape(event.getFormattedMessage()),
                escape(stackTrace)
        );

        return "{\"text\":\"" + escapeJson(text) + "\"}";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("Bearer ", "Bearer ***")
                .replaceAll("(?i)(authorization:)[^\\n\\r]+", "$1 ***")
                .replaceAll("(?i)(api[_-]?key=)[^\\s&]+", "$1***")
                .replaceAll("(?i)(token=)[^\\s&]+", "$1***");
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
