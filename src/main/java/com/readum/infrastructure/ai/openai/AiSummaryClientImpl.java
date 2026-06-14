package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.model.aiChat.entity.AiChatMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiSummaryClientImpl implements AiSummaryClient {

    // TODO: 컨텍스트 윈도우 초과 방지를 위해 최근 N턴만 사용하는 절삭 로직 추가 필요
    //       현재는 전체 이력을 그대로 전달함 (세션 이력이 짧은 초기 단계에서는 무방)
    private static final String USER_TURN_PREFIX = "User: ";
    private static final String ASSISTANT_TURN_PREFIX = "Assistant: ";

    // 감사 로그용 프롬프트 식별자. summary-generation.st 프롬프트를 바꾸면 버전을 올린다.
    private static final String PROMPT_TEMPLATE_ID = "summary-generation";
    private static final String PROMPT_TEMPLATE_VERSION = "v1";

    private final ChatClient chatClient;
    private final AiPromptAuditLogger auditLogger;

    @Value("classpath:prompts/summary-generation.st")
    private Resource summaryPromptResource;

    private String summaryPromptTemplate;

    @PostConstruct
    public void init() {
        try {
            summaryPromptTemplate = summaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("summary-generation.st 프롬프트 파일을 로드하지 못했습니다.", e);
        }
    }

    private static final ResponseFormat SUMMARY_RESPONSE_FORMAT = ResponseFormat.builder()
            .type(ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(ResponseFormat.JsonSchema.builder()
                    .name("summary_draft")
                    .strict(true)
                    .schema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "title", Map.of("type", "string"),
                                    "body", Map.of("type", "string")
                            ),
                            "required", List.of("title", "body"),
                            "additionalProperties", false
                    ))
                    .build())
            .build();

    @Override
    public SummaryDraftResult generate(List<AiChatMessage> messages) {
        String chatHistory = formatChatHistory(messages);
        log.debug("[Summary] 대화 이력 포맷 완료 - 메시지 수: {}", messages.size());

        AiPromptAuditEvent baseEvent = AiPromptAuditEvent.started(
                conversationIdHash(messages),
                PROMPT_TEMPLATE_ID,
                PROMPT_TEMPLATE_VERSION,
                auditLogger.sha256(chatHistory)
        );
        long startNanos = System.nanoTime();
        try {
            // entity() 대신 responseEntity() 로 받아 구조화 결과와 함께 토큰/모델 메타데이터를 감사 로그에 남긴다.
            ResponseEntity<ChatResponse, SummaryDraftResult> responseEntity = chatClient.prompt()
                    .system(summaryPromptTemplate)
                    .user("[대화 이력]\n" + chatHistory + "\n\n위 대화 이력을 바탕으로 감상문 초안을 작성해 주세요.")
                    .options(OpenAiChatOptions.builder()
                            .responseFormat(SUMMARY_RESPONSE_FORMAT)
                            .build())
                    .call()
                    .responseEntity(SummaryDraftResult.class);
            auditLogger.success(ChatResponseAuditMapper.applyResult(
                    baseEvent, responseEntity.getResponse(), elapsedMillis(startNanos)));
            return responseEntity.getEntity();
        } catch (RuntimeException e) {
            auditLogger.failure(baseEvent.completed(null, 0, 0, 0, elapsedMillis(startNanos)), e);
            throw e;
        }
    }

    private String conversationIdHash(List<AiChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        Long sessionId = messages.get(0).getSessionId();
        return sessionId == null ? null : auditLogger.sha256(String.valueOf(sessionId));
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String formatChatHistory(List<AiChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (AiChatMessage message : messages) {
            String prefix = switch (message.getRole()) {
                case USER -> USER_TURN_PREFIX;
                case ASSISTANT -> ASSISTANT_TURN_PREFIX;
            };
            sb.append(prefix).append(message.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}
