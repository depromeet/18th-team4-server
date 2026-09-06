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
 * 생성 중에는 행을 만들지 않고(진행 상태는 summary_job.PROCESSING 이 표현한다),
 * 생성에 성공했을 때만 한 번 기록된다. 실패 시에는 행을 만들지 않는다 (원인은 로그로만 남긴다).
 * 세션과 1:1 — unique 제약(uk_summary_session)으로 세션당 감상문은 한 행만 허용된다. 감상문 완성 시 세션은 종료(LOCKED)되어 재생성이 없다.
 */
@Getter
@Entity
@Table(
        name = "summary",
        uniqueConstraints = {
                @jakarta.persistence.UniqueConstraint(
                        name = "uk_summary_session", columnNames = "ai_chat_session_id")
        },
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
     * 생성에 성공한 감상문을 한 행으로 기록한다.
     * 종료 모델에서 세션당 한 번만 생성된다.
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
