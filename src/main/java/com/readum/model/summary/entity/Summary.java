package com.readum.model.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "summary",
        indexes = {
                @Index(name = "idx_summary_user_book", columnList = "user_book_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_summary_chat_session", columnNames = "chat_session_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Summary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_book_id", nullable = false)
    private Long userBookId;

    @Column(name = "chat_session_id", nullable = false)
    private Long chatSessionId;

    @Column(name = "quote", columnDefinition = "TEXT")
    private String quote;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static Summary create(
            Long userBookId,
            Long chatSessionId,
            String quote,
            String title,
            String body
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(null, userBookId, chatSessionId, quote, title, body, now, now);
    }

    public static Summary of(
            Long id,
            Long userBookId,
            Long chatSessionId,
            String quote,
            String title,
            String body,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        return new Summary(id, userBookId, chatSessionId, quote, title, body, createdAt, updatedAt);
    }
}
