package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.UserTokenBudget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserTokenBudgetRepositoryTest {

    private static final int PERIOD_KEY = 20260904;
    private static final long MAX_BUDGET = 1_000L;

    @Autowired
    private UserTokenBudgetRepository userTokenBudgetRepository;

    private static long userSeq = 900_000L;

    private static synchronized long nextUserId() {
        return userSeq++;
    }

    @Test
    void 행_보장_upsert_는_두_번_호출해도_기존_행을_덮지_않고_1행을_유지한다() {
        long userId = nextUserId();
        LocalDateTime now = LocalDateTime.now();
        userTokenBudgetRepository.upsertLedgerRow(userId, PERIOD_KEY, MAX_BUDGET, now);
        userTokenBudgetRepository.reserveIfWithinBudget(userId, PERIOD_KEY, 100, now);

        // 이미 있는 행에 대한 재호출은 no-op — 사용량·한도 스냅샷이 초기화되지 않는다.
        userTokenBudgetRepository.upsertLedgerRow(userId, PERIOD_KEY, 9_999L, now);

        UserTokenBudget ledgerRow = userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, PERIOD_KEY).orElseThrow();
        assertThat(ledgerRow.getUsedTokens()).isEqualTo(100L);
        assertThat(ledgerRow.getMaxBudget()).isEqualTo(MAX_BUDGET);
    }

    @Test
    void 조건부_예약_UPDATE_는_한도_안이면_1행을_갱신하고_사용량을_늘린다() {
        long userId = nextUserId();
        LocalDateTime now = LocalDateTime.now();
        userTokenBudgetRepository.upsertLedgerRow(userId, PERIOD_KEY, MAX_BUDGET, now);

        int reservedRowCount = userTokenBudgetRepository.reserveIfWithinBudget(userId, PERIOD_KEY, 600, now);

        assertThat(reservedRowCount).isEqualTo(1);
        UserTokenBudget ledgerRow = userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, PERIOD_KEY).orElseThrow();
        assertThat(ledgerRow.getUsedTokens()).isEqualTo(600L);
    }

    @Test
    void 조건부_예약_UPDATE_는_한도를_넘으면_0행_갱신으로_거절되고_사용량이_변하지_않는다() {
        long userId = nextUserId();
        LocalDateTime now = LocalDateTime.now();
        userTokenBudgetRepository.upsertLedgerRow(userId, PERIOD_KEY, MAX_BUDGET, now);
        userTokenBudgetRepository.reserveIfWithinBudget(userId, PERIOD_KEY, 600, now);

        int reservedRowCount = userTokenBudgetRepository.reserveIfWithinBudget(userId, PERIOD_KEY, 500, now);

        assertThat(reservedRowCount).isZero();
        UserTokenBudget ledgerRow = userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, PERIOD_KEY).orElseThrow();
        assertThat(ledgerRow.getUsedTokens()).isEqualTo(600L);
    }

    @Test
    void 사용량_증감_UPDATE_는_정산_보정과_환불_델타를_그대로_반영한다() {
        long userId = nextUserId();
        LocalDateTime now = LocalDateTime.now();
        userTokenBudgetRepository.upsertLedgerRow(userId, PERIOD_KEY, MAX_BUDGET, now);
        userTokenBudgetRepository.reserveIfWithinBudget(userId, PERIOD_KEY, 600, now);

        // 정산 보정(실측 − 예약 = -160) → 440, 환불(-440) → 0
        userTokenBudgetRepository.applyUsedTokenDelta(userId, PERIOD_KEY, -160L, now);
        assertThat(userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, PERIOD_KEY).orElseThrow()
                .getUsedTokens()).isEqualTo(440L);

        userTokenBudgetRepository.applyUsedTokenDelta(userId, PERIOD_KEY, -440L, now);
        assertThat(userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, PERIOD_KEY).orElseThrow()
                .getUsedTokens()).isZero();
    }
}
