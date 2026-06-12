package com.readum.model.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "summary",
        indexes = {
                @Index(name = "idx_summary_user_book", columnList = "user_book_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_summary_ai_chat_session", columnNames = "ai_chat_session_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class Summary {

    public enum Status {
        IN_PROGRESS, COMPLETED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_book_id", nullable = false)
    private Long userBookId;

    @Column(name = "ai_chat_session_id", nullable = false)
    private Long aiChatSessionId;

    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

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

    /**
     * 감상문 생성 시작 시점에 IN_PROGRESS 상태로 레코드를 먼저 생성한다.
     * content(title/body/quote)는 AI 응답 후 complete() 로 채운다.
     * summaryDate 는 요약 생성을 요청한(수동/자동) 날짜 (캘린더 표시 기준).
     */
    public static Summary createInProgress(Long userBookId, Long aiChatSessionId, LocalDate summaryDate) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(
                null, userBookId, aiChatSessionId, summaryDate,
                Status.IN_PROGRESS, null, null, null, now, now
        );
    }

    public void complete(String title, String body, String quote) {
        this.title = title;
        this.body = body;
        this.quote = quote;
        this.status = Status.COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = Status.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isCompleted() {
        return this.status == Status.COMPLETED;
    }
}
