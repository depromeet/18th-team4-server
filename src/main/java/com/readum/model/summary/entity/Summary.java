package com.readum.model.summary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 감상문 — 생성에 성공한 결과의 불변 기록 (write-once).
 * 생성 중에는 행을 만들지 않고(세션 AiChatSession.Status.LOCKED 가 "생성 중" 을 표현),
 * 생성에 성공했을 때만 한 번 기록된다. 실패 시에는 행을 만들지 않는다 (원인은 로그로만 남긴다).
 * 세션과 1:N — 재생성할 때마다 새 행이 추가되며 "현재 감상문" 은 가장 최근 행이다 (과거 행은 이력 보존).
 */
@Getter
@Entity
@Table(
        name = "summary",
        indexes = {
                @Index(name = "idx_summary_user_book", columnList = "user_book_id"),
                @Index(name = "idx_summary_session", columnList = "ai_chat_session_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class Summary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_book_id", nullable = false)
    private Long userBookId;

    @Column(name = "ai_chat_session_id", nullable = false)
    private Long aiChatSessionId;

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
     * 생성에 성공한 감상문을 새 행으로 기록한다.
     * 재생성은 기존 행을 고치지 않고 새 행을 추가한다 ("현재 감상문" = 가장 최근 행).
     */
    public static Summary createCompleted(Long userBookId, Long aiChatSessionId, String title, String body) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(null, userBookId, aiChatSessionId, null, title, body, now, now);
    }

    /**
     * 사용자가 자기 감상문의 제목·본문을 직접 다듬는 편집. 생성과는 별개 행위이므로 제자리 수정한다.
     */
    public void edit(String title, String body) {
        this.title = title;
        this.body = body;
        this.updatedAt = LocalDateTime.now();
    }
}
