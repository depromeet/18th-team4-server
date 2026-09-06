package com.readum.model.aiChat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 토큰 예산의 일 단위 원장 행 — (user_id, period_key=KST 날짜) 당 1행.
 * 행 생성은 repository 의 원자 UPSERT(native), 사용량 증감은 조건부 원자 UPDATE 로만 일어나므로
 * 이 엔티티는 create() 도메인 팩토리 없이 조회 전용 매핑으로만 쓴다.
 * max_budget 은 행 생성 시점 정책값(daily-tokens)의 스냅샷 — 이후 정책이 바뀌어도 그날 한도는 고정된다.
 */
@Getter
@Entity
@Table(
        name = "user_token_budget",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_token_budget_user_period", columnNames = {"user_id", "period_key"})
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTokenBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** KST 달력 날짜의 정수 표기 (예: 20260904). 하루가 한 예산 기간이다. */
    @Column(name = "period_key", nullable = false)
    private int periodKey;

    /** 예약(선불) 포함 사용 토큰 합계. 정산 시 실측으로 보정, 환불 시 예약분 차감. */
    @Column(name = "used_tokens", nullable = false)
    private long usedTokens;

    @Column(name = "max_budget", nullable = false)
    private long maxBudget;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
