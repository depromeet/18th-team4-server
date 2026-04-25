package com.readum.model.chat.entity;

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
        name = "chat_message",
        indexes = {
                @Index(name = "idx_chat_message_session", columnList = "session_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChatMessage {

    public enum Role {
        USER, ASSISTANT, SYSTEM
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ChatMessage create(
            Long sessionId,
            Role role,
            String content,
            String quoteText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {
        return new ChatMessage(null, sessionId, role, content, quoteText, inputTokens, outputTokens, totalTokens, LocalDateTime.now());
    }

    public static ChatMessage of(
            Long id,
            Long sessionId,
            Role role,
            String content,
            String quoteText,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            LocalDateTime createdAt
    ) {
        return new ChatMessage(id, sessionId, role, content, quoteText, inputTokens, outputTokens, totalTokens, createdAt);
    }
}
