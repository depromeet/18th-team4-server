package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.model.aiChat.entity.AiChatTokenSettlement;
import com.readum.model.aiChat.repository.AiChatTokenSettlementRepository;
import com.readum.model.aiChat.repository.UserTokenBudgetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 사용자 토큰 예산 원장(user_token_budget)의 원자적 DB 쓰기 구간.
 * 예약·정산·환불 각각을 선언적 {@code @Transactional} 로 묶는 협력자 빈
 * (transaction.md — 외부 호출이 섞인 서비스 흐름에서 DB 쓰기만 분리).
 * 원장은 DB 가 정본이라 우회(fail-open) 경로가 없다 — DB 예외는 그대로 전파한다
 * (DB 장애면 채팅 요청 자체가 어차피 실패한다).
 * 시간 출처: period_key 와 행 타임스탬프 모두 KST 로 통일한다 — 호스트 시간대가 UTC 여도
 * 감사용 원장 한 행 안에서 period_key(KST 날짜)와 created_at 이 어긋나지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class UserTokenBudgetWriter {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    private final UserTokenBudgetRepository userTokenBudgetRepository;
    private final AiChatTokenSettlementRepository aiChatTokenSettlementRepository;
    private final AiChatProperties aiChatProperties;

    /** 예약 결과 — Granted 는 정산·환불에 필요한 원장 좌표(periodKey)와 예약량을 담는다. */
    sealed interface ReserveResult {
        record Granted(int periodKey, int reservedTokens) implements ReserveResult {
        }

        record Denied(Duration retryAfter) implements ReserveResult {
        }
    }

    /**
     * 추정 토큰을 오늘(KST) 예산에서 선점한다.
     * ① 원자 UPSERT 로 오늘 원장 행을 보장하고(max_budget 은 이 시점 정책값 스냅샷),
     * ② "used_tokens + 예약량 <= max_budget" 조건부 원자 UPDATE 한 문장으로 예약과 한도 검사를
     * 동시에 수행한다. 갱신 0행이면 한도 초과 — Denied(다음 KST 자정까지 남은 시간).
     */
    @Transactional
    public ReserveResult reserve(Long userId, int estimatedTokens) {
        TokenBudgetPeriod period = TokenBudgetPeriod.current(Clock.system(ZONE_KST));
        LocalDateTime now = LocalDateTime.now(ZONE_KST);
        userTokenBudgetRepository.upsertLedgerRow(
                userId, period.periodKey(), aiChatProperties.tokenBudget().dailyTokens(), now);
        int reservedRowCount = userTokenBudgetRepository.reserveIfWithinBudget(
                userId, period.periodKey(), estimatedTokens, now);
        if (reservedRowCount == 0) {
            return new ReserveResult.Denied(period.untilNextMidnight());
        }
        return new ReserveResult.Granted(period.periodKey(), estimatedTokens);
    }

    /**
     * 성공 정산: ① 멱등 기록 INSERT(message_id UNIQUE) → ② 사용량 보정(실측 − 예약) UPDATE 를
     * 한 트랜잭션으로 묶는다. 이미 정산된 메시지면 ① 이 UNIQUE 위반으로 실패하며 트랜잭션 전체가
     * 롤백되어 이중 정산이 구조적으로 차단된다 — 호출부는 {@code DataIntegrityViolationException} 을
     * "이미 정산됨" 으로 처리한다.
     */
    @Transactional
    public void settle(Long userId, int periodKey, Long messageId, int reservedTokens, int actualTotalTokens) {
        aiChatTokenSettlementRepository.saveAndFlush(
                AiChatTokenSettlement.create(userId, messageId, actualTotalTokens));
        int adjustedRowCount = userTokenBudgetRepository.applyUsedTokenDelta(
                userId, periodKey, (long) actualTotalTokens - reservedTokens, LocalDateTime.now(ZONE_KST));
        if (adjustedRowCount == 0) {
            log.warn("토큰 예산 보정 대상 원장 행 없음 — 보정 미반영 userId={} periodKey={} messageId={}",
                    userId, periodKey, messageId);
        }
    }

    /** 환불: 예약분 전액 차감 — moderation 차단/불능, 게이트 거절, 생성·저장 실패 경로에서 호출된다. */
    @Transactional
    public void refund(Long userId, int periodKey, int reservedTokens) {
        int adjustedRowCount = userTokenBudgetRepository.applyUsedTokenDelta(
                userId, periodKey, -((long) reservedTokens), LocalDateTime.now(ZONE_KST));
        if (adjustedRowCount == 0) {
            log.warn("토큰 예산 환불 대상 원장 행 없음 — 환불 미반영 userId={} periodKey={}", userId, periodKey);
        }
    }
}
