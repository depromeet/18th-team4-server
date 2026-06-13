package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.out.AiSummaryClient;
import com.readum.model.aiChat.entity.AiChatMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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

    private final ChatClient chatClient;

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

        return chatClient.prompt()
                .system(summaryPromptTemplate)
                .user("[대화 이력]\n" + chatHistory + "\n\n위 대화 이력을 바탕으로 감상문 초안을 작성해 주세요.")
                .options(OpenAiChatOptions.builder()
                        .responseFormat(SUMMARY_RESPONSE_FORMAT)
                        .build())
                .call()
                .entity(SummaryDraftResult.class);
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
