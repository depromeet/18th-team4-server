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
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class AiChatSession {

    public enum Status {
        ACTIVE, LOCKED
    }

    private static final int TITLE_MAX_LENGTH = 100;

    // 제목 생성(첫 응답 후 비동기)이 끝나기 전이나 실패했을 때 빈 제목이 노출되지 않도록,
    // 세션 생성 시점에 박아두는 기본 제목. 생성에 성공하면 updateTitle 이 덮어쓴다.
    public static final String DEFAULT_TITLE = "새로운 대화";

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
        return new AiChatSession(null, userBookId, Status.ACTIVE, 0, 0, DEFAULT_TITLE, now, now);
    }

    /**
     * 감상문 생성에 성공하면 세션을 영구 잠근다(LOCKED). 이후 이 세션에서는 대화할 수 없다.
     * (구 모델의 "생성 중 일시 잠금" 이 아니라 "완료 후 종료" 를 뜻한다 — 되돌리지 않는다.)
     */
    public void lock() {
        this.status = Status.LOCKED;
        this.updatedAt = LocalDateTime.now();
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

    public boolean isLocked() {
        return this.status == Status.LOCKED;
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
