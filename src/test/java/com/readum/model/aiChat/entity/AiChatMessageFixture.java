package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 테스트에서 특정 id·역할·상태의 {@link AiChatMessage} 를 만들기 위한 조립기.
 * 운영 엔티티에 있던 {@code AiChatMessage.of(...)} 와 범용 {@code AiChatMessage.create(...)} 를 대체한다.
 * 뜻이 분명한 {@code createUserMessage/createAssistantSuccess/createAssistantFailed} 는 엔티티에 그대로 둔다.
 */
@TestOnly
public final class AiChatMessageFixture {

    private AiChatMessageFixture() {
    }

    public static AiChatMessage of(
            Long id,
            Long sessionId,
            AiChatMessage.Role role,
            String content,
            String quoteText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            AiChatMessage.Status status,
            LocalDateTime createdAt
    ) {
        AiChatMessage message = new AiChatMessage();
        ReflectionTestUtils.setField(message, "id", id);
        ReflectionTestUtils.setField(message, "sessionId", sessionId);
        ReflectionTestUtils.setField(message, "role", role);
        ReflectionTestUtils.setField(message, "content", content);
        ReflectionTestUtils.setField(message, "quoteText", quoteText);
        ReflectionTestUtils.setField(message, "inputTokens", inputTokens);
        ReflectionTestUtils.setField(message, "outputTokens", outputTokens);
        ReflectionTestUtils.setField(message, "totalTokens", totalTokens);
        ReflectionTestUtils.setField(message, "status", status);
        ReflectionTestUtils.setField(message, "createdAt", createdAt);
        return message;
    }

    /**
     * 제거된 범용 {@code AiChatMessage.create(...)} 와 같은 시그니처. id 는 미지정,
     * createdAt 은 호출 시점. 안전 팩토리로 못 만드는 조합(예: SYSTEM 역할) 에 쓴다.
     */
    public static AiChatMessage create(
            Long sessionId,
            AiChatMessage.Role role,
            AiChatMessage.Status status,
            String content,
            String quoteText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {
        return of(null, sessionId, role, content, quoteText,
                inputTokens, outputTokens, totalTokens, status, LocalDateTime.now());
    }
}
