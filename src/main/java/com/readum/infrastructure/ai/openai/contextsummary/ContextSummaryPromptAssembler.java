package com.readum.infrastructure.ai.openai.contextsummary;

import com.readum.model.aiChat.entity.AiChatMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 컨텍스트 누적 요약 프롬프트 조립 컴포넌트. 증분 방식: [이전 요약] + [새 대화 원문] → 완전한 새 누적 요약.
 * {@link AiContextSummaryClientImpl} 이 시스템 프롬프트·사용자 메시지·응답 형식을 여기서 가져온다.
 */
@Slf4j
@Component
public class ContextSummaryPromptAssembler {

    private static final String USER_TURN_PREFIX = "User: ";
    private static final String ASSISTANT_TURN_PREFIX = "Assistant: ";

    /** OpenAI response_format 스키마 이름. */
    public static final String RESPONSE_FORMAT_SCHEMA_NAME = "chat_context_summary";

    @Value("classpath:prompts/chat-context-summarizer.st")
    private Resource promptResource;

    private String systemPrompt;

    @PostConstruct
    public void init() {
        try {
            systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("chat-context-summarizer.st 프롬프트 파일을 로드하지 못했습니다.", e);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * OpenAI 응답 형식(response_format) JSON 스키마 — {@code {"content": string}}.
     * 길이·토큰 지시는 넣지 않는다(스펙 8절: 길이 제어는 숫자가 아니라 섹션 틀·병합 지침으로).
     */
    public Map<String, Object> responseFormatSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content", Map.of("type", "string")
                ),
                "required", List.of("content"),
                "additionalProperties", false
        );
    }

    /** 사용자 메시지: [이전 요약] + [새 대화] 를 조립한다. 이전 요약이 없으면(첫 요약) 그 표시만 다르게 한다. */
    public String buildUserMessage(String previousSummary, List<AiChatMessage> deltaMessages) {
        String previous = (previousSummary == null || previousSummary.isBlank())
                ? "(없음 — 첫 요약)"
                : previousSummary;
        return "[이전 요약]\n" + previous
                + "\n\n[새 대화]\n" + formatChatHistory(deltaMessages)
                + "\n\n위 [이전 요약]에 [새 대화]를 병합해 갱신된 누적 요약을 작성해 주세요.";
    }

    /** 대화 원문을 "User: ..." / "Assistant: ..." 문자열로 변환한다. */
    public String formatChatHistory(List<AiChatMessage> messages) {
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
