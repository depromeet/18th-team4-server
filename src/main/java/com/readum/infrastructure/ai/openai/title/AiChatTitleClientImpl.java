package com.readum.infrastructure.ai.openai.title;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatTitleClient;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.infrastructure.ai.openai.ChatResponseAuditMapper;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatTitleClientImpl implements AiChatTitleClient {

    // 감사 로그용 프롬프트 식별자. title-generator-system.st 프롬프트를 바꾸면 버전을 올린다.
    private static final String PROMPT_TEMPLATE_ID = "title-generator-system";
    private static final String PROMPT_TEMPLATE_VERSION = "v1";

    /** 제목은 한 줄 출력 — 게이트 계상용 출력 추정값. */
    private static final int ESTIMATED_OUTPUT_TOKENS = 64;

    // 제목 생성 전용 ChatClient(10초 responseTimeout, advisor 미적용). 빈 이름으로 주입해 채팅용 chatClient 와 구분한다.
    private final ChatClient titleGenerationChatClient;
    private final AiPromptAuditLogger auditLogger;
    private final OpenAiRequestGate requestGate;
    private final TokenCounter tokenCounter;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Value("classpath:prompts/title-generator-system.st")
    private Resource systemPromptResource;

    private String systemPrompt;

    @PostConstruct
    void init() throws IOException {
        // 제목 생성 전용 ChatClient 에는 defaultSystem 을 두지 않으므로 호출 시 .system() 으로 제목 프롬프트를 지정한다.
        // 매 호출마다 파일을 읽지 않도록 startup 시 1번만 로드해 캐시.
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public String generate(List<AiChatMessage> messages) {
        String chatHistory = formatChatHistory(messages);

        // 전역 게이트: 포화면 이번 회차 생략. 던진 예외는 AiChatTitleGenerationListener 의
        // error consumer 가 삼킨다 — 기존 제목 생성 실패 처리와 동일한 경로다 (저빈도·실패 허용).
        int estimatedTokens = tokenCounter.count(systemPrompt) + tokenCounter.count(chatHistory)
                + ESTIMATED_OUTPUT_TOKENS;
        OpenAiRequestGate.Decision decision = requestGate.tryAcquire(chatModel, estimatedTokens);
        if (decision instanceof OpenAiRequestGate.Decision.Rejected rejected) {
            AiChatErrorCode code = rejected.reason() == OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN
                    ? AiChatErrorCode.AI_QUOTA_EXHAUSTED
                    : AiChatErrorCode.AI_RATE_LIMIT_BURST;
            throw new TooManyRequestsException(
                    code,
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
            // content() 대신 chatResponse() 로 받아 토큰/모델 메타데이터를 감사 로그에 남긴다.
            ChatResponse response = titleGenerationChatClient.prompt()
                    .system(systemPrompt)
                    .user(chatHistory)
                    .call()
                    .chatResponse();
            auditLogger.success(ChatResponseAuditMapper.applyResult(baseEvent, response, elapsedMillis(startNanos)));
            return extractText(response);
        } catch (RuntimeException e) {
            auditLogger.failure(baseEvent.completed(null, 0, 0, 0, elapsedMillis(startNanos)), e);
            throw e;
        }
    }

    private String extractText(ChatResponse response) {
        String raw = Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(result -> result.getOutput())
                .map(output -> output.getText())
                .orElse(null);
        return raw == null ? "" : raw.strip();
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
                case USER -> "User: ";
                case ASSISTANT -> "Assistant: ";
            };
            sb.append(prefix).append(message.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}
