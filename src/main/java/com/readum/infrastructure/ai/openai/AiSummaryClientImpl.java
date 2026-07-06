package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiTokenEstimate;
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

@Slf4j
@Component
public class AiSummaryClientImpl implements AiSummaryClient {

    // TODO: 컨텍스트 윈도우 초과 방지를 위해 최근 N턴만 사용하는 절삭 로직 추가 필요
    //       현재는 전체 이력을 그대로 전달함 (세션 이력이 짧은 초기 단계에서는 무방)

    // 감사 로그용 프롬프트 식별자. summary-generation.st 프롬프트를 바꾸면 버전을 올린다.
    private static final String PROMPT_TEMPLATE_ID = "summary-generation";
    private static final String PROMPT_TEMPLATE_VERSION = "v1";

    private final ChatClient chatClient;
    private final AiPromptAuditLogger auditLogger;
    // 시스템 프롬프트·대화 이력 포맷·응답 스키마를 Batch API 어댑터와 공유한다.
    private final SummaryPromptAssembler promptAssembler;
    // 응답 스키마 구조는 promptAssembler 에서 가져와 인스턴스 필드로 보관한다.
    // static 필드가 아닌 이유: ResponseFormat 조립에 promptAssembler 인스턴스가 필요하기 때문.
    private final ResponseFormat summaryResponseFormat;
    private final OpenAiRequestGate requestGate;
    // domain 의 config record 를 infrastructure 가 읽는 것은 허용 방향(infrastructure → domain).
    private final SummaryJobProperties summaryJobProperties;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    public AiSummaryClientImpl(
            ChatClient chatClient,
            AiPromptAuditLogger auditLogger,
            SummaryPromptAssembler promptAssembler,
            OpenAiRequestGate requestGate,
            SummaryJobProperties summaryJobProperties
    ) {
        this.chatClient = chatClient;
        this.auditLogger = auditLogger;
        this.promptAssembler = promptAssembler;
        this.requestGate = requestGate;
        this.summaryJobProperties = summaryJobProperties;
        this.summaryResponseFormat = ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder()
                        .name(SummaryPromptAssembler.RESPONSE_FORMAT_SCHEMA_NAME)
                        .strict(true)
                        .schema(promptAssembler.responseFormatSchema())
                        .build())
                .build();
    }

    @Override
    public SummaryDraftResult generate(List<AiChatMessage> messages) {
        String chatHistory = promptAssembler.formatChatHistory(messages);
        log.debug("[Summary] 대화 이력 포맷 완료 - 메시지 수: {}", messages.size());

        // 전역 게이트: 포화면 burst 429 로 던진다 — 워커의 handleRateLimited 가
        // "브레이커 잠깐 차단(Retry-After 만큼) + 무벌점 반납" 으로 처리한다 (기존 경로 재사용).
        int estimatedTokens = OpenAiTokenEstimate.fromChars(
                (long) promptAssembler.systemPrompt().length() + chatHistory.length())
                + summaryJobProperties.estimatedOutputTokens();
        OpenAiRequestGate.Decision decision = requestGate.tryAcquire(chatModel, estimatedTokens);
        if (decision instanceof OpenAiRequestGate.Decision.Rejected rejected) {
            throw new TooManyRequestsException(
                    AiChatErrorCode.AI_RATE_LIMIT_BURST,
                    new RateLimitInfo(rejected.retryAfter(), null, null, null, null, null, null));
        }

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
                    .system(promptAssembler.systemPrompt())
                    .user(promptAssembler.buildUserMessage(messages))
                    .options(OpenAiChatOptions.builder()
                            .responseFormat(summaryResponseFormat)
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
}
