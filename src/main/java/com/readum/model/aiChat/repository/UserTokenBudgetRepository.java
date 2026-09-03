package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.UserTokenBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserTokenBudgetRepository extends JpaRepository<UserTokenBudget, Long> {

    /**
     * 오늘 원장 행을 원자적으로 보장한다(UPSERT). 이미 있으면 no-op 갱신 — SELECT 후 INSERT 로
     * 나누면 동시 요청이 중복 INSERT 로 충돌하므로 한 문장으로 처리한다.
     * max_budget 은 행 생성 시점 정책값의 스냅샷이라 이미 있는 행은 덮어쓰지 않는다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_token_budget (user_id, period_key, used_tokens, max_budget, created_at, updated_at)
            VALUES (:userId, :periodKey, 0, :maxBudget, :now, :now)
            ON DUPLICATE KEY UPDATE user_id = user_id
            """, nativeQuery = true)
    int upsertLedgerRow(
            @Param("userId") Long userId,
            @Param("periodKey") int periodKey,
            @Param("maxBudget") long maxBudget,
            @Param("now") LocalDateTime now);

    /**
     * 예약과 한도 검사를 조건부 원자 UPDATE 한 문장으로 수행한다.
     * 갱신된 행이 0이면 한도 초과(거절) — 검사와 증가 사이에 다른 요청이 끼어들 틈이 없다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserTokenBudget userTokenBudget
               set userTokenBudget.usedTokens = userTokenBudget.usedTokens + :estimatedTokens
                 , userTokenBudget.updatedAt = :now
             where userTokenBudget.userId = :userId
               and userTokenBudget.periodKey = :periodKey
               and userTokenBudget.usedTokens + :estimatedTokens <= userTokenBudget.maxBudget
            """)
    int reserveIfWithinBudget(
            @Param("userId") Long userId,
            @Param("periodKey") int periodKey,
            @Param("estimatedTokens") int estimatedTokens,
            @Param("now") LocalDateTime now);

    /** 사용량 증감 — 정산 보정(실측 − 예약)과 환불(−예약분)이 같은 원자 UPDATE 를 쓴다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserTokenBudget userTokenBudget
               set userTokenBudget.usedTokens = userTokenBudget.usedTokens + :usedTokenDelta
                 , userTokenBudget.updatedAt = :now
             where userTokenBudget.userId = :userId
               and userTokenBudget.periodKey = :periodKey
            """)
    int applyUsedTokenDelta(
            @Param("userId") Long userId,
            @Param("periodKey") int periodKey,
            @Param("usedTokenDelta") long usedTokenDelta,
            @Param("now") LocalDateTime now);

    Optional<UserTokenBudget> findByUserIdAndPeriodKey(Long userId, int periodKey);
}
