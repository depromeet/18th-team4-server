package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link AiChatMessage} 를 만드는 명명 팩토리.
 * 메시지의 역할(USER/ASSISTANT)·완료 상태를 이름으로 드러낸다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
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
        return new AiChatMessage(
                id, sessionId, AiChatMessage.Role.USER, content, null,
                null, null, null, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
    }

    /**
     * 저장되어 id 가 부여된, COMPLETED 상태의 ASSISTANT 메시지. 인용/토큰 없음.
     */
    public static AiChatMessage persistedAssistantMessage(Long id, Long sessionId, String content) {
        return new AiChatMessage(
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
        return new AiChatMessage(
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
        return new AiChatMessage(
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
        return new AiChatMessage(
                null, sessionId, AiChatMessage.Role.ASSISTANT, content, null,
                null, outputTokens, totalTokens, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
    }

    /**
     * 특정 시각에 작성된, 저장 전(id 미부여) COMPLETED USER 메시지.
     * 마지막 채팅 시각 정렬 같은 createdAt 의존 조회 검증용.
     */
    public static AiChatMessage userMessageAt(Long sessionId, String content, LocalDateTime createdAt) {
        return new AiChatMessage(
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
        return new AiChatMessage(
                null, sessionId, AiChatMessage.Role.ASSISTANT, partialContent, null,
                1, 0, 1, AiChatMessage.Status.FAILED, createdAt
        );
    }

    /**
     * save() 가 id 를 부여해 돌려준 영속 메시지를 모사한다 — source 의 모든 필드를 그대로,
     * id 만 부여해 복제. repository.save 의 willAnswer 스텁용.
     */
    public static AiChatMessage persistedCopyOf(Long id, AiChatMessage source) {
        return new AiChatMessage(
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
}
