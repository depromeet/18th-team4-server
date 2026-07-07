package com.readum.model.aiChat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 세션당 1행의 누적 요약 — 원문(ai_chat_message)에서 재생성 가능한 파생 데이터.
 * {@code summarizedUntilMessageId} 경계로 "요약이 커버하는 구간"과 "원문 꼬리"를 정확히 나눈다:
 * 조립은 "요약 + 경계 이후 원문 전부"이므로 중복도 구멍도 구조적으로 없다.
 * 경계는 항상 완결된 턴의 끝(ASSISTANT 메시지 id)을 가리켜, 원문 꼬리가 USER 로 시작하게 한다.
 * {@code version} 은 워커의 낙관적 갱신(늦게 돌아온 옛 워커가 최신 요약을 덮어쓰지 못하게)용.
 */
@Getter
@Entity
@Table(
        name = "ai_chat_context_summary",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ai_chat_context_summary_session", columnNames = "session_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class AiChatContextSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "summarized_until_message_id", nullable = false)
    private Long summarizedUntilMessageId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 세션의 첫 요약. version=1 로 시작한다. */
    public static AiChatContextSummary create(
            Long sessionId, String content, Long summarizedUntilMessageId, int tokenCount
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new AiChatContextSummary(
                null, sessionId, content, summarizedUntilMessageId, 1, tokenCount, now, now);
    }

    /**
     * 새 누적 요약으로 갱신하고 version 을 올린다. 경계는 단조 증가해야 한다(호출 전 검증).
     * 늦게 돌아온 옛 워커의 덮어쓰기 방지는 서비스가 {@code version} 일치 확인 후에만 이 메서드를 호출해 보장한다.
     */
    public void applyUpdate(String content, Long summarizedUntilMessageId, int tokenCount) {
        this.content = content;
        this.summarizedUntilMessageId = summarizedUntilMessageId;
        this.version += 1;
        this.tokenCount = tokenCount;
        this.updatedAt = LocalDateTime.now();
    }
}
