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
        ACTIVE, SUMMARIZING, CLOSED
    }

    private static final int TITLE_MAX_LENGTH = 100;

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

    @Column(name = "title", length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static AiChatSession create(Long userBookId) {
        LocalDateTime now = LocalDateTime.now();
        return new AiChatSession(null, userBookId, Status.ACTIVE, 0, 0, null, now, now);
    }

    public void markSummarizing() {
        this.status = Status.SUMMARIZING;
        this.updatedAt = LocalDateTime.now();
    }

    public void revertToActive() {
        this.status = Status.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void close() {
        this.status = Status.CLOSED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return this.status == Status.ACTIVE;
    }

    public boolean isSummarizing() {
        return this.status == Status.SUMMARIZING;
    }

    public static AiChatSession of(
            Long id,
            Long userBookId,
            Status status,
            int userMessageCount,
            int accumulatedTokens,
            String title,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        return new AiChatSession(id, userBookId, status, userMessageCount, accumulatedTokens, title, createdAt, updatedAt);
    }

    public void appendUserMessage() {
        this.userMessageCount += 1;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isFirstUserMessage() {
        return this.userMessageCount == 1;
    }

    // 트리거 무관 generic 갱신. 첫 메시지 트리거뿐 아니라 향후 N턴 재생성·수동 재명명에서도 호출.
    public void updateTitle(String title) {
        this.title = truncateTitle(title);
        this.updatedAt = LocalDateTime.now();
    }

    public boolean hasTitle() {
        return this.title != null && !this.title.isBlank();
    }

    // ASSISTANT 가 생성한 토큰 (output) 만 누적. 입력 프롬프트는 매 턴 중복되므로 합산 대상이 아니다.
    public void addAssistantTokens(int outputTokens) {
        this.accumulatedTokens += outputTokens;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isClosed() {
        return this.status == Status.CLOSED;
    }

    private static String truncateTitle(String title) {
        if (title == null) {
            return null;
        }
        return title.length() <= TITLE_MAX_LENGTH
                ? title
                : title.substring(0, TITLE_MAX_LENGTH);
    }
}
