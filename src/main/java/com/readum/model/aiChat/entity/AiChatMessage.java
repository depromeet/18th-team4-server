package com.readum.model.aiChat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "ai_chat_message",
        indexes = {
                @Index(name = "idx_ai_chat_message_session_created_id", columnList = "session_id, created_at, id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiChatMessage {

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }

    /**
     * COMPLETED : USER 메시지(즉시 저장) 또는 정상 종료된 ASSISTANT 메시지.
     * FAILED    : 스트림 비정상 종료 시 부분 응답을 보존하기 위한 ASSISTANT 메시지 상태.
     *             컨텍스트 윈도우(findRecentForContextWindow) 에서 자동 제외된다.
     */
    public enum Status {
        COMPLETED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "quote_text", columnDefinition = "TEXT")
    private String quoteText;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static AiChatMessage createUserMessage(Long sessionId, String content) {
        return new AiChatMessage(
                null,
                sessionId,
                Role.USER,
                content,
                null,
                null,
                null,
                null,
                Status.COMPLETED,
                LocalDateTime.now()
        );
    }

    public static AiChatMessage createAssistantSuccess(
            Long sessionId,
            String content,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {
        return new AiChatMessage(
                null,
                sessionId,
                Role.ASSISTANT,
                content,
                null,
                inputTokens,
                outputTokens,
                totalTokens,
                Status.COMPLETED,
                LocalDateTime.now()
        );
    }

    public static AiChatMessage createAssistantFailed(
            Long sessionId,
            String partialContent,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {
        return new AiChatMessage(
                null,
                sessionId,
                Role.ASSISTANT,
                partialContent,
                null,
                inputTokens,
                outputTokens,
                totalTokens,
                Status.FAILED,
                LocalDateTime.now()
        );
    }

    public static AiChatMessage create(
            Long sessionId,
            Role role,
            Status status,
            String content,
            String quoteText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {
        return new AiChatMessage(
                null, sessionId, role, content, quoteText,
                inputTokens, outputTokens, totalTokens, status, LocalDateTime.now()
        );
    }

    public static AiChatMessage of(
            Long id,
            Long sessionId,
            Role role,
            String content,
            String quoteText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            Status status,
            LocalDateTime createdAt
    ) {
        return new AiChatMessage(id, sessionId, role, content, quoteText, inputTokens, outputTokens, totalTokens, status, createdAt);
    }
}
