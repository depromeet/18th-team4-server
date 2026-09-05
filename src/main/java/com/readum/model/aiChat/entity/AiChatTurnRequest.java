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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 채팅 한 턴의 요청 기록 — 클라이언트가 발급한 requestId 의 멱등 판정과 예약 복구의 정본.
 *
 * <p>(user_id, request_id) UNIQUE 가 재전송 중복을 DB 에서 원자적으로 막는다. 조회 후 삽입이 아니라
 * <b>삽입 시 유일 위반</b>으로 판정하므로 같은 ID 가 동시에 들어와도 정확히 한 건만 통과한다.
 * 소유자를 유일 범위에 포함해, 서로 다른 사용자가 같은 문자열을 써도 서로를 막지 않는다.
 *
 * <p>상태 흐름: ACCEPTED → RESERVED → SUCCEEDED / FAILED / EXPIRED.
 * <ul>
 *   <li>ACCEPTED — 중복 판정을 통과해 자리를 잡았다. 아직 예산 예약 전이라 되돌릴 예약이 없다.</li>
 *   <li>RESERVED — 예산 예약과 예약 정보(reservedTokens·budgetPeriodKey)가 <b>같은 트랜잭션</b>으로
 *       커밋됐다. 생성·후처리가 진행 중인 상태이기도 하다. 이 상태의 미종료 행이 예약 반환의 대상이다.</li>
 *   <li>SUCCEEDED / FAILED / EXPIRED — 종료. 종료된 요청에는 저장·정산·반환을 다시 반영하지 않는다.</li>
 * </ul>
 *
 * <p>종료 전이(markSucceeded·markFailed·markExpired)는 요청 종료 트랜잭션
 * (AiChatTurnOutcomeWriter)이 같은 행을 잠그고 미종료임을 확인한 뒤에만 부른다. 엔티티는 상태 값만
 * 바꾸며 예산 반영·답변 저장을 알지 못한다 — 그 원자성은 트랜잭션 경계의 몫이다.
 *
 * <p>시간 출처는 KST 로 통일한다 — 예산 기간(budgetPeriodKey)이 KST 달력 날짜라,
 * 한 행 안에서 기간 키와 타임스탬프가 어긋나지 않게 한다.
 */
@Getter
@Entity
@Table(
        name = "ai_chat_turn_request",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ai_chat_turn_request_user_request",
                        columnNames = {"user_id", "request_id"})
        },
        indexes = {
                @Index(name = "idx_ai_chat_turn_request_recovery", columnList = "status, expires_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class AiChatTurnRequest {

    public enum Status {
        ACCEPTED, RESERVED, SUCCEEDED, FAILED, EXPIRED;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == EXPIRED;
        }
    }

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 요청 소유자 — requestId 유일 범위의 일부다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 클라이언트가 발급하고 재전송 때 그대로 유지하는 요청 식별자. */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    /** 예약이 반영된 예산 기간(KST 날짜 yyyyMMdd) — 예약 전에는 비어 있다. */
    @Column(name = "budget_period_key")
    private Integer budgetPeriodKey;

    /** 예약량 R — 예약 전에는 비어 있고, 반환할 양이 정확히 이 값이다. */
    @Column(name = "reserved_tokens")
    private Integer reservedTokens;

    /** 성공 확정 시 저장된 ASSISTANT 메시지 id — 저장·정산 트랜잭션이 채운다. */
    @Column(name = "assistant_message_id")
    private Long assistantMessageId;

    /** 실패 사유 표식 (ErrorCode 이름 등) — 운영 확인용이며 분기 조건으로 쓰지 않는다. */
    @Column(name = "failure_code", length = 50)
    private String failureCode;

    /** 만료 판정 기준 시각 = 생성 시각 + 만료 유예. 이 시각을 넘긴 미종료 행이 복구 대상이다. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 중복 판정을 통과한 새 요청. 아직 예약 전이라 예약 정보는 비어 있다.
     * expiresAt 은 생성 시각에서 파생해 한 시점에서 함께 정한다 — 만료 유예 설정이 나중에 바뀌어도
     * 이미 접수된 요청의 만료 시각이 뒤늦게 흔들리지 않는다.
     */
    public static AiChatTurnRequest createAccepted(
            Long userId, Long sessionId, String requestId, Duration expiryTimeout) {
        LocalDateTime now = LocalDateTime.now(ZONE_KST);
        return new AiChatTurnRequest(
                null, userId, sessionId, requestId, Status.ACCEPTED,
                null, null, null, null, now.plus(expiryTimeout), now, now
        );
    }

    /** 예산 예약이 확정됐다 — 반환에 필요한 좌표(기간 키)와 양을 같은 트랜잭션에서 함께 남긴다. */
    public void markReserved(int budgetPeriodKey, int reservedTokens) {
        this.status = Status.RESERVED;
        this.budgetPeriodKey = budgetPeriodKey;
        this.reservedTokens = reservedTokens;
        this.updatedAt = LocalDateTime.now(ZONE_KST);
    }

    /**
     * 성공으로 종료한다 — 저장된 ASSISTANT 메시지와 연결한다.
     * 답변 저장·정산·예산 보정과 <b>같은 트랜잭션</b>에서 불러야 셋 중 하나만 남는 상태가 생기지 않는다.
     */
    public void markSucceeded(Long assistantMessageId) {
        this.status = Status.SUCCEEDED;
        this.assistantMessageId = assistantMessageId;
        this.updatedAt = LocalDateTime.now(ZONE_KST);
    }

    /** 실패로 종료한다. 예약 반환 자체는 예산 원장 쪽 작업이며 이 전이는 요청 상태만 끝낸다. */
    public void markFailed(String failureCode) {
        this.status = Status.FAILED;
        this.failureCode = failureCode;
        this.updatedAt = LocalDateTime.now(ZONE_KST);
    }

    /**
     * 만료로 종료한다 — 기한이 지나도록 끝나지 않은 요청을 복구 작업이 정리할 때 쓴다.
     * 상태 이름만 FAILED 와 다르다: 무엇이 요청을 끝냈는지(생성 실패 vs 기한 경과)를 나중에 구분하려는 것이고,
     * 사용자에게 청구하지 않고 예약을 되돌린다는 처리는 같다.
     */
    public void markExpired(String failureCode) {
        this.status = Status.EXPIRED;
        this.failureCode = failureCode;
        this.updatedAt = LocalDateTime.now(ZONE_KST);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 되돌릴 예약이 이 행에 남아 있는가. 예약 전(ACCEPTED)에 끝난 요청은 반환할 것이 없다.
     * 예약량과 예산 기간은 함께 커밋되므로 둘 중 하나만 있는 상태는 정상 흐름에서 나오지 않는다.
     */
    public boolean hasReservation() {
        return reservedTokens != null && budgetPeriodKey != null;
    }
}
