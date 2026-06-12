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
     */
    public static Summary createInProgress(Long userBookId, Long aiChatSessionId) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(null, userBookId, aiChatSessionId, Status.IN_PROGRESS, null, null, null, now, now);
    }

    /**
     * 생성 성공으로 끝난 감상문 기록.
     * 감상문 행은 생성 시도가 끝난 시점에 결과와 함께 한 번만 만들어지며 이후 변경되지 않는다.
     * "생성 중" 상태는 이 엔티티가 아니라 세션(AiChatSession.Status.LOCKED) 이 표현한다.
     */
    public static Summary createCompleted(
            Long userBookId, Long aiChatSessionId, String title, String body, String quote
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(null, userBookId, aiChatSessionId, Status.COMPLETED, quote, title, body, now, now);
    }

    /**
     * 생성 실패로 끝난 시도의 기록.
     * 세션은 실패 후 다시 활성화되므로, 폴링 응답("생성 실패")의 영속 근거이자 품질 감사용 흔적으로 남긴다.
     */
    public static Summary createFailed(Long userBookId, Long aiChatSessionId) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(null, userBookId, aiChatSessionId, Status.FAILED, null, null, null, now, now);
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
