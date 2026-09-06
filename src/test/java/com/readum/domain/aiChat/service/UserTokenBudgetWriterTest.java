package com.readum.domain.aiChat.service;

import com.readum.model.aiChat.repository.AiChatTokenSettlementRepository;
import com.readum.model.aiChat.repository.UserTokenBudgetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

/**
 * H2 원장에 대한 통합 테스트. 정산 롤백처럼 커밋 경계가 필요한 검증이 있어
 * 테스트 트랜잭션으로 감싸지 않고(@Transactional 없음) Writer 의 자체 트랜잭션으로 커밋한 뒤
 * 커밋된 상태를 단언한다. 남긴 행은 @AfterEach 에서 정리한다.
 */
@SpringBootTest
class UserTokenBudgetWriterTest {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private UserTokenBudgetWriter userTokenBudgetWriter;

    @Autowired
    private UserTokenBudgetRepository userTokenBudgetRepository;

    @Autowired
    private AiChatTokenSettlementRepository aiChatTokenSettlementRepository;

    private static long userSeq = 950_000L;
    private static long messageSeq = 960_000L;

    private static synchronized long nextUserId() {
        return userSeq++;
    }

    private static synchronized long nextMessageId() {
        return messageSeq++;
    }

    @AfterEach
    void cleanUp() {
        aiChatTokenSettlementRepository.deleteAll();
        userTokenBudgetRepository.deleteAll();
    }

    private static int todayPeriodKey() {
        LocalDate today = LocalDate.now(ZONE_KST);
        return today.getYear() * 10_000 + today.getMonthValue() * 100 + today.getDayOfMonth();
    }

    private long usedTokensOf(long userId, int periodKey) {
        return userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, periodKey).orElseThrow().getUsedTokens();
    }

    @Test
    void 예약이_한도_안이면_Granted_로_오늘_KST_periodKey_와_예약량을_돌려주고_원장에_기록한다() {
        long userId = nextUserId();

        UserTokenBudgetWriter.ReserveResult result = userTokenBudgetWriter.reserve(userId, 513);

        assertThat(result)
                .asInstanceOf(type(UserTokenBudgetWriter.ReserveResult.Granted.class))
                .satisfies(granted -> {
                    assertThat(granted.periodKey()).isEqualTo(todayPeriodKey());
                    assertThat(granted.reservedTokens()).isEqualTo(513);
                });
        assertThat(usedTokensOf(userId, todayPeriodKey())).isEqualTo(513L);
    }

    @Test
    void 한도를_넘는_예약은_Denied_와_다음_KST_자정까지의_retryAfter_로_거절되고_사용량은_늘지_않는다() {
        long userId = nextUserId();

        // 테스트 설정의 일일 예산 120,000 을 한 번에 초과하는 예약
        UserTokenBudgetWriter.ReserveResult result = userTokenBudgetWriter.reserve(userId, 120_001);

        assertThat(result)
                .asInstanceOf(type(UserTokenBudgetWriter.ReserveResult.Denied.class))
                .satisfies(denied -> {
                    assertThat(denied.retryAfter()).isPositive();
                    assertThat(denied.retryAfter()).isLessThanOrEqualTo(Duration.ofHours(24));
                });
        assertThat(usedTokensOf(userId, todayPeriodKey())).isZero();
    }

    @Test
    void 정산은_실측과_예약의_차이로_사용량을_보정하고_멱등_기록을_남긴다() {
        long userId = nextUserId();
        long messageId = nextMessageId();
        int periodKey = todayPeriodKey();
        userTokenBudgetWriter.reserve(userId, 513);

        userTokenBudgetWriter.settle(userId, periodKey, messageId, 513, 300);

        assertThat(usedTokensOf(userId, periodKey)).isEqualTo(300L);
        assertThat(aiChatTokenSettlementRepository.existsByMessageId(messageId)).isTrue();
    }

    @Test
    void 같은_메시지의_중복_정산은_예외를_던지고_사용량을_바꾸지_않는다() {
        long userId = nextUserId();
        long messageId = nextMessageId();
        int periodKey = todayPeriodKey();
        userTokenBudgetWriter.reserve(userId, 513);
        userTokenBudgetWriter.settle(userId, periodKey, messageId, 513, 300);

        assertThatThrownBy(() -> userTokenBudgetWriter.settle(userId, periodKey, messageId, 513, 280))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 멱등 기록 INSERT 가 먼저 실패하므로 보정 UPDATE 는 반영되지 않는다(트랜잭션 단위 차단).
        assertThat(usedTokensOf(userId, periodKey)).isEqualTo(300L);
    }

    @Test
    void 환불은_예약분_전액을_사용량에서_차감한다() {
        long userId = nextUserId();
        int periodKey = todayPeriodKey();
        userTokenBudgetWriter.reserve(userId, 513);

        userTokenBudgetWriter.refund(userId, periodKey, 513);

        assertThat(usedTokensOf(userId, periodKey)).isZero();
    }
}
