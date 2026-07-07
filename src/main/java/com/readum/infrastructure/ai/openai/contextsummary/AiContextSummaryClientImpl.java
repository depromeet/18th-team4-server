package com.readum.infrastructure.ai.openai.contextsummary;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.domain.aiChat.out.AiContextSummaryClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.infrastructure.ai.openai.ChatResponseAuditMapper;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRateLimitGuard;
import com.readum.model.aiChat.entity.AiChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 컨텍스트 누적 요약 동기 생성 어댑터. 감상문 AiSummaryClientImpl 과 같은 골격 —
 * 전역 게이트 검사(포화 시 burst 429 / quota 쿨다운) → 감사 로그 → 구조화 응답.
 * max_tokens 하드 스톱은 걸지 않는다(응답이 잘리면 요약 정보가 유실됨 — 스펙 8절, 전역 설정에서도 제거).
 */
@Slf4j
@Component
public class AiContextSummaryClientImpl implements AiContextSummaryClient {

    private static final String PROMPT_TEMPLATE_ID = "chat-context-summarizer";
    private static final String PROMPT_TEMPLATE_VERSION = "v1";

    private final ChatClient contextSummaryChatClient;
    private final AiPromptAuditLogger auditLogger;
    private final ContextSummaryPromptAssembler promptAssembler;
    private final ResponseFormat responseFormat;
    private final OpenAiRateLimitGuard rateLimitGuard;
    private final AiChatProperties aiChatProperties;
    private final TokenCounter tokenCounter;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    public AiContextSummaryClientImpl(
            ChatClient contextSummaryChatClient,
            AiPromptAuditLogger auditLogger,
            ContextSummaryPromptAssembler promptAssembler,
            OpenAiRateLimitGuard rateLimitGuard,
            AiChatProperties aiChatProperties,
            TokenCounter tokenCounter
    ) {
        this.contextSummaryChatClient = contextSummaryChatClient;
        this.auditLogger = auditLogger;
        this.promptAssembler = promptAssembler;
        this.rateLimitGuard = rateLimitGuard;
        this.aiChatProperties = aiChatProperties;
        this.tokenCounter = tokenCounter;
        this.responseFormat = ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder()
                        .name(ContextSummaryPromptAssembler.RESPONSE_FORMAT_SCHEMA_NAME)
                        .strict(true)
                        .schema(promptAssembler.responseFormatSchema())
                        .build())
                .build();
    }

    @Override
    public ContextSummaryResult generate(String previousSummary, List<AiChatMessage> deltaMessages) {
        String userMessage = promptAssembler.buildUserMessage(previousSummary, deltaMessages);

        // 전역 게이트: 포화면 burst 429, quota 소진이면 쿨다운으로 던진다 — 워커가 각각 무벌점 반납/재큐로 처리한다.
        // 계상은 실제 전송량(시스템 프롬프트 + 사용자 메시지 + 예약 출력). 사용자 예산에는 미계상(시스템이 시키는 호출).
        int estimatedTokens = tokenCounter.count(promptAssembler.systemPrompt())
                + tokenCounter.count(userMessage)
                + aiChatProperties.context().summaryEstimatedOutputTokens();
        rateLimitGuard.acquireOrThrow(chatModel, estimatedTokens);

        AiPromptAuditEvent baseEvent = AiPromptAuditEvent.started(
                conversationIdHash(deltaMessages),
                PROMPT_TEMPLATE_ID,
                PROMPT_TEMPLATE_VERSION,
                auditLogger.sha256(userMessage)
        );
        long startNanos = System.nanoTime();
        try {
            ResponseEntity<ChatResponse, ContextSummaryResult> responseEntity = contextSummaryChatClient.prompt()
                    .system(promptAssembler.systemPrompt())
                    .user(userMessage)
                    .options(OpenAiChatOptions.builder()
                            .responseFormat(responseFormat)
                            .build())
                    .call()
                    .responseEntity(ContextSummaryResult.class);
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
}
