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
import java.time.ZoneId;

/**
 * 생성 턴 1회의 토큰 정산 멱등 기록 — message_id UNIQUE 위반이 이중 정산을 구조적으로 차단한다.
 * 멱등 키가 message_id 인 이유(잠정): 클라이언트 requestId 는 후속 API 변경(별도 작업)에서
 * 도입 예정이라, 그 전까지 "생성 턴당 정산 1회"를 구조적으로 보장하는 키는
 * 저장된 ASSISTANT 메시지 id 다.
 */
@Getter
@Entity
@Table(
        name = "ai_chat_token_settlement",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ai_chat_token_settlement_message", columnNames = "message_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiChatTokenSettlement {

    /** 원장 계열 테이블은 시간 출처를 KST 로 통일한다 — period_key(KST 날짜)와 타임스탬프가 어긋나지 않게. */
    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 정산 대상 ASSISTANT 메시지 id — 정산 멱등 키. */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** 실측 총 토큰 (메시지 입력 추정 + 실측 출력). */
    @Column(name = "settled_tokens", nullable = false)
    private int settledTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static AiChatTokenSettlement create(Long userId, Long messageId, int settledTokens) {
        return new AiChatTokenSettlement(null, userId, messageId, settledTokens, LocalDateTime.now(ZONE_KST));
    }
}
