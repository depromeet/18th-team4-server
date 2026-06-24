package com.readum.infrastructure.ai.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * AI 호출 메타데이터를 별도 감사 로그 파일(ai-prompt-audit.log)에 JSON 한 줄로 기록한다.
 *
 * <p>"AI_PROMPT_AUDIT" 로거로 기록하며, 이 로거는 logback-spring.xml 의 dev/prod 프로파일에서
 * 전용 롤링 파일 appender 로 연결되어 있다.
 *
 * <p>AI 호출 어댑터(infrastructure/ai/openai 의 {@code *ClientImpl}) 가 호출 전후로
 * {@link #success}/{@link #failure} 를 불러 실제 감사 로그를 남긴다. prompt/completion 원문은
 * 절대 담지 않고, 식별이 필요한 값은 {@link #sha256} 해시로만 보관한다.
 */
@Component
public class AiPromptAuditLogger {

    private static final Logger AI_AUDIT_LOGGER = LoggerFactory.getLogger("AI_PROMPT_AUDIT");

    private final ObjectMapper objectMapper;

    public AiPromptAuditLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void success(AiPromptAuditEvent event) {
        write(event.withStatus("success", null));
    }

    public void failure(AiPromptAuditEvent event, Throwable throwable) {
        write(event.withStatus("failed", throwable.getClass().getName()));
    }

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "sha256:error";
        }
    }

    private void write(AiPromptAuditEvent event) {
        try {
            Map<String, Object> payload = Map.ofEntries(
                    Map.entry("event", "ai.prompt.call"),
                    Map.entry("timestamp", Instant.now().toString()),
                    Map.entry("traceId", valueOrDash(MDC.get("traceId"))),
                    Map.entry("spanId", valueOrDash(MDC.get("spanId"))),
                    Map.entry("conversationIdHash", valueOrDash(event.conversationIdHash())),
                    Map.entry("promptTemplateId", valueOrDash(event.promptTemplateId())),
                    Map.entry("promptTemplateVersion", valueOrDash(event.promptTemplateVersion())),
                    Map.entry("model", valueOrDash(event.model())),
                    Map.entry("inputTokens", event.inputTokens()),
                    Map.entry("outputTokens", event.outputTokens()),
                    Map.entry("totalTokens", event.totalTokens()),
                    Map.entry("latencyMs", event.latencyMs()),
                    Map.entry("status", valueOrDash(event.status())),
                    Map.entry("errorClass", valueOrDash(event.errorClass())),
                    Map.entry("promptHash", valueOrDash(event.promptHash())),
                    Map.entry("redacted", true)
            );

            AI_AUDIT_LOGGER.info(objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            AI_AUDIT_LOGGER.info("{\"event\":\"ai.prompt.call\",\"status\":\"audit_log_json_failed\"}");
        }
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
