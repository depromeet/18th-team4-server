package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link AiChatMessage} 를 만드는 명명 팩토리.
 * 메시지의 역할(USER/ASSISTANT)·완료 상태를 이름으로 드러낸다.
 * 운영 엔티티의 invariant 를 우회하지 않도록 엔티티 생성자는 PRIVATE 으로 두고,
 * 픽스처는 같은 패키지에서 접근 가능한 protected 무인자 생성자로 객체를 만든 뒤
 * {@link ReflectionTestUtils} 로 필드를 채운다 (테스트 전용).
 * 뜻이 분명한 {@code createUserMessage/createAssistantSuccess/createAssistantFailed} 도메인 팩토리는 엔티티에 그대로 둔다.
 */
@TestOnly
public final class AiChatMessageFixture {

    private AiChatMessageFixture() {
    }

    /**
     * 저장되어 id 가 부여된, COMPLETED 상태의 USER 메시지. 인용/토큰 없음.
     */
    public static AiChatMessage persistedUserMessage(Long id, Long sessionId, String content) {
        return assemble(
                id, sessionId, AiChatMessage.Role.USER, content, null,
                null, null, null, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
    }

    /**
     * 저장되어 id 가 부여된, COMPLETED 상태의 ASSISTANT 메시지. 인용/토큰 없음.
     */
    public static AiChatMessage persistedAssistantMessage(Long id, Long sessionId, String content) {
        return assemble(
                id, sessionId, AiChatMessage.Role.ASSISTANT, content, null,
                null, null, null, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
    }

    /**
     * 저장되어 id 가 부여된, COMPLETED 상태의 ASSISTANT 메시지. 인용/토큰 집계를 가진다.
     */
    public static AiChatMessage persistedAssistantMessage(
            Long id,
            Long sessionId,
            String content,
            String quoteText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {
        return assemble(
                id, sessionId, AiChatMessage.Role.ASSISTANT, content, quoteText,
                inputTokens, outputTokens, totalTokens, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
    }

    /**
     * 아직 저장 전(id 미부여)인 COMPLETED USER 메시지. 토큰 집계를 가진다.
     * 감상문 초안 입력 이력 구성용.
     */
    public static AiChatMessage userMessageWithTokens(
            Long sessionId, String content, Integer inputTokens, Integer totalTokens
    ) {
        return assemble(
                null, sessionId, AiChatMessage.Role.USER, content, null,
                inputTokens, null, totalTokens, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
    }

    /**
     * 아직 저장 전(id 미부여)인 COMPLETED ASSISTANT 메시지. 토큰 집계를 가진다.
     * 감상문 초안 입력 이력 구성용.
     */
    public static AiChatMessage assistantMessageWithTokens(
            Long sessionId, String content, Integer outputTokens, Integer totalTokens
    ) {
        return assemble(
                null, sessionId, AiChatMessage.Role.ASSISTANT, content, null,
                null, outputTokens, totalTokens, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
    }

    /**
     * 특정 시각에 작성된, 저장 전(id 미부여) COMPLETED USER 메시지.
     * 마지막 채팅 시각 정렬 같은 createdAt 의존 조회 검증용.
     */
    public static AiChatMessage userMessageAt(Long sessionId, String content, LocalDateTime createdAt) {
        return assemble(
                null, sessionId, AiChatMessage.Role.USER, content, null,
                10, null, 10, AiChatMessage.Status.COMPLETED, createdAt
        );
    }

    /**
     * 특정 시각에 작성된, 저장 전(id 미부여) FAILED ASSISTANT 부분 응답.
     * 조회 필터가 FAILED 부분 응답을 제외하는지 검증하는 용도.
     */
    public static AiChatMessage failedAssistantMessageAt(
            Long sessionId, String partialContent, LocalDateTime createdAt
    ) {
        return assemble(
                null, sessionId, AiChatMessage.Role.ASSISTANT, partialContent, null,
                1, 0, 1, AiChatMessage.Status.FAILED, createdAt
        );
    }

    /**
     * save() 가 id 를 부여해 돌려준 영속 메시지를 모사한다 — source 의 모든 필드를 그대로,
     * id 만 부여해 복제. repository.save 의 willAnswer 스텁용.
     */
    public static AiChatMessage persistedCopyOf(Long id, AiChatMessage source) {
        return assemble(
                id,
                source.getSessionId(),
                source.getRole(),
                source.getContent(),
                source.getQuoteText(),
                source.getInputTokens(),
                source.getOutputTokens(),
                source.getTotalTokens(),
                source.getStatus(),
                source.getCreatedAt()
        );
    }

    private static AiChatMessage assemble(
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
}
