package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.out.AiChatTitleClient;
import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
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

    private final ChatClient chatClient;
    private final AiPromptAuditLogger auditLogger;

    @Value("classpath:prompts/title-generator-system.st")
    private Resource systemPromptResource;

    private String systemPrompt;

    @PostConstruct
    void init() throws IOException {
        // ChatClient 의 defaultSystem 은 reading-assistant 용으로 고정돼 있어,
        // 제목 생성 호출 시에는 .system() 오버라이드로 프롬프트를 교체한다.
        // 매 호출마다 파일을 읽지 않도록 startup 시 1번만 로드해 캐시.
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public String generate(List<AiChatMessage> messages) {
        String chatHistory = formatChatHistory(messages);
        AiPromptAuditEvent baseEvent = AiPromptAuditEvent.started(
                conversationIdHash(messages),
                PROMPT_TEMPLATE_ID,
                PROMPT_TEMPLATE_VERSION,
                auditLogger.sha256(chatHistory)
        );
        long startNanos = System.nanoTime();
        try {
            // content() 대신 chatResponse() 로 받아 토큰/모델 메타데이터를 감사 로그에 남긴다.
            ChatResponse response = chatClient.prompt()
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
