package com.readum.infrastructure.ai.openai.summary;

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
 * 감상문 초안 생성용 프롬프트 조립 공통 컴포넌트.
 * {@link AiSummaryClientImpl}(동기 단건 경로)이 시스템 프롬프트·대화 이력 포맷·응답 형식을 여기서 가져온다.
 */
@Slf4j
@Component
public class SummaryPromptAssembler {

    private static final String USER_TURN_PREFIX = "User: ";
    private static final String ASSISTANT_TURN_PREFIX = "Assistant: ";

    /**
     * OpenAI API 에 전달할 response_format 스키마 이름.
     * Batch API JSONL 에서 "name" 필드로 사용한다.
     */
    public static final String RESPONSE_FORMAT_SCHEMA_NAME = "summary_draft";

    @Value("classpath:prompts/summary-generation.st")
    private Resource summaryPromptResource;

    private String systemPrompt;

    @PostConstruct
    public void init() {
        try {
            systemPrompt = summaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("summary-generation.st 프롬프트 파일을 로드하지 못했습니다.", e);
        }
    }

    /**
     * 감상문 생성 시스템 프롬프트 문자열을 반환한다.
     * {@code classpath:prompts/summary-generation.st} 에서 읽은 원문 그대로다.
     */
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * OpenAI API 응답 형식(response_format)에 사용하는 JSON 스키마 구조를 반환한다.
     *
     * <p>동기 단건 경로({@link AiSummaryClientImpl})가 쓰는 스키마 구조의 유일한 출처다.
     *
     * <p>반환 구조:
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "title": {"type": "string"},
     *     "body":  {"type": "string"}
     *   },
     *   "required": ["title", "body"],
     *   "additionalProperties": false
     * }
     * </pre>
     */
    public Map<String, Object> responseFormatSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "body", Map.of("type", "string")
                ),
                "required", List.of("title", "body"),
                "additionalProperties", false
        );
    }

    /**
     * OpenAI API 에 보낼 사용자 메시지 문자열을 조립한다.
     * 형식: {@code "[대화 이력]\n{이력}\n\n위 대화 이력을 바탕으로 감상문 초안을 작성해 주세요."}
     */
    public String buildUserMessage(List<AiChatMessage> messages) {
        String formattedHistory = formatChatHistory(messages);
        return "[대화 이력]\n" + formattedHistory + "\n\n위 대화 이력을 바탕으로 감상문 초안을 작성해 주세요.";
    }

    /**
     * 대화 이력을 "User: ..." / "Assistant: ..." 형태의 문자열로 변환한다.
     * 각 메시지는 역할 접두어 + 내용 + 줄바꿈으로 구성되며, 전체 앞뒤 공백은 제거한다.
     */
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
