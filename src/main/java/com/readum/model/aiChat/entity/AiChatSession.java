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
        name = "ai_chat_session",
        indexes = {
                @Index(name = "idx_ai_chat_session_user_book", columnList = "user_book_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiChatSession {

    public enum Status {
        ACTIVE, CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_book_id", nullable = false)
    private Long userBookId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "user_message_count", nullable = false)
    private int userMessageCount;

    @Column(name = "accumulated_tokens", nullable = false)
    private int accumulatedTokens;

    @Column(name = "last_message_preview", length = 500)
    private String lastMessagePreview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static AiChatSession create(Long userBookId) {
        LocalDateTime now = LocalDateTime.now();
        return new AiChatSession(null, userBookId, Status.ACTIVE, 0, 0, null, now, now);
    }

    public void close() {
        this.status = Status.CLOSED;
        this.updatedAt = LocalDateTime.now();
    }

    public static AiChatSession of(
            Long id,
            Long userBookId,
            Status status,
            int userMessageCount,
            int accumulatedTokens,
            String lastMessagePreview,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        return new AiChatSession(id, userBookId, status, userMessageCount, accumulatedTokens, lastMessagePreview, createdAt, updatedAt);
    }
}
