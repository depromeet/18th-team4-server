package com.readum.domain.aiChat.service;

import com.readum.model.aiChat.entity.AiChatTurnRequest;
import com.readum.model.aiChat.repository.AiChatTurnRequestRepository;
import com.readum.model.aiChat.repository.UserTokenBudgetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요청 기록 협력자의 통합 테스트. 트랜잭션 경계 자체가 검증 대상이라 테스트 트랜잭션으로 감싸지 않고
 * (@Transactional 없음) Writer 의 자체 트랜잭션으로 커밋한 뒤 커밋된 상태를 단언한다.
 * 남긴 행은 @AfterEach 에서 정리한다.
 *
 * <p>여기서 쓰는 DB 는 H2(MySQL 호환 모드)다 — 잠금·유일성의 운영 DB 의미는
 * 수동 실행 테스트(AiChatTurnRequestMySqlUniquenessTest)가 MySQL 에 대고 따로 확인한다.
 */
@SpringBootTest
class AiChatTurnRequestWriterTest {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");
    private static final Duration EXPIRY_TIMEOUT = Duration.ofMinutes(3);

    @Autowired
    private AiChatTurnRequestWriter aiChatTurnRequestWriter;

    @Autowired
    private AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter;

    @Autowired
    private AiChatTurnRequestRepository aiChatTurnRequestRepository;

    @Autowired
    private UserTokenBudgetRepository userTokenBudgetRepository;

    private static long userSeq = 980_000L;

    private static synchronized long nextUserId() {
        return userSeq++;
    }

    @AfterEach
    void cleanUp() {
        aiChatTurnRequestRepository.deleteAll();
        userTokenBudgetRepository.deleteAll();
    }

    private static int todayPeriodKey() {
        LocalDate today = LocalDate.now(ZONE_KST);
        return today.getYear() * 10_000 + today.getMonthValue() * 100 + today.getDayOfMonth();
    }

    private long usedTokensOf(long userId) {
        return userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, todayPeriodKey())
                .map(ledgerRow -> ledgerRow.getUsedTokens())
                .orElse(0L);
    }

    @Test
    void 같은_사용자의_같은_요청_식별자를_다시_받으면_유일_위반을_밖으로_내보낸다() {
        long userId = nextUserId();
        aiChatTurnRequestWriter.claim(userId, 7L, "request-a", EXPIRY_TIMEOUT);

        // 판정은 조회가 아니라 삽입이다 — 호출부가 이 예외를 중복 응답으로 바꾼다.
        assertThatThrownBy(() -> aiChatTurnRequestWriter.claim(userId, 7L, "request-a", EXPIRY_TIMEOUT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 사용자가_다르면_같은_요청_식별자도_각각_자리를_잡는다() {
        long firstUserId = nextUserId();
        long secondUserId = nextUserId();
        aiChatTurnRequestWriter.claim(firstUserId, 7L, "shared-id", EXPIRY_TIMEOUT);

        assertThatCode(() -> aiChatTurnRequestWriter.claim(secondUserId, 9L, "shared-id", EXPIRY_TIMEOUT))
                .doesNotThrowAnyException();
    }

    @Test
    void 같은_식별자가_동시에_들어와도_정확히_한_건만_자리를_잡는다() throws Exception {
        long userId = nextUserId();
        int concurrentRequestCount = 16;

        List<Boolean> claimed;
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrentRequestCount)) {
            List<Callable<Boolean>> concurrentClaims = java.util.stream.IntStream.range(0, concurrentRequestCount)
                    .<Callable<Boolean>>mapToObj(index -> () -> {
                        try {
                            aiChatTurnRequestWriter.claim(userId, 7L, "race-id", EXPIRY_TIMEOUT);
                            return true;
                        } catch (DataIntegrityViolationException duplicate) {
                            return false;
                        }
                    })
                    .toList();
            List<Future<Boolean>> results = executor.invokeAll(concurrentClaims);
            claimed = results.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception unexpected) {
                    throw new IllegalStateException(unexpected);
                }
            }).toList();
        }

        assertThat(claimed.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(aiChatTurnRequestRepository.findByUserIdAndRequestId(userId, "race-id")).isPresent();
    }

    @Test
    void 예약이_허용되면_예산_증가와_예약_정보_기록이_함께_커밋된다() {
        long userId = nextUserId();
        Long turnRequestId = aiChatTurnRequestWriter.claim(userId, 7L, "request-b", EXPIRY_TIMEOUT);

        UserTokenBudgetWriter.ReserveResult reserveResult =
                aiChatTurnRequestWriter.reserveWithRecord(turnRequestId, userId, 300);

        assertThat(reserveResult).isInstanceOf(UserTokenBudgetWriter.ReserveResult.Granted.class);
        assertThat(usedTokensOf(userId)).isEqualTo(300L);
        AiChatTurnRequest turnRequest = aiChatTurnRequestRepository.findById(turnRequestId).orElseThrow();
        assertThat(turnRequest.getStatus()).isEqualTo(AiChatTurnRequest.Status.RESERVED);
        assertThat(turnRequest.getReservedTokens()).isEqualTo(300);
        assertThat(turnRequest.getBudgetPeriodKey()).isEqualTo(todayPeriodKey());
    }

    @Test
    void 예약_정보_기록이_실패하면_예산_증가도_함께_롤백된다() {
        long userId = nextUserId();
        long missingTurnRequestId = 9_999_999L;

        // 요청 행을 찾지 못해 예약 정보를 남길 수 없으면, 되돌릴 양을 모르는 예약이 생기지 않게 예산도 되돌린다.
        assertThatThrownBy(() -> aiChatTurnRequestWriter.reserveWithRecord(missingTurnRequestId, userId, 300))
                .isInstanceOf(RuntimeException.class);

        assertThat(usedTokensOf(userId)).isZero();
        assertThat(userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, todayPeriodKey())).isEmpty();
    }

    @Test
    void 예산이_거절되면_요청_기록에_예약_정보가_남지_않는다() {
        long userId = nextUserId();
        Long turnRequestId = aiChatTurnRequestWriter.claim(userId, 7L, "request-c", EXPIRY_TIMEOUT);

        // 하루 한도(테스트 설정 120000)를 넘는 예약은 조건부 UPDATE 가 0행으로 거절한다.
        UserTokenBudgetWriter.ReserveResult reserveResult =
                aiChatTurnRequestWriter.reserveWithRecord(turnRequestId, userId, 999_999);

        assertThat(reserveResult).isInstanceOf(UserTokenBudgetWriter.ReserveResult.Denied.class);
        AiChatTurnRequest turnRequest = aiChatTurnRequestRepository.findById(turnRequestId).orElseThrow();
        assertThat(turnRequest.getStatus()).isEqualTo(AiChatTurnRequest.Status.ACCEPTED);
        assertThat(turnRequest.getReservedTokens()).isNull();
        assertThat(usedTokensOf(userId)).isZero();
    }

    @Test
    void 실패로_끝낸_요청의_식별자를_다시_보내도_새_요청으로_받지_않는다() {
        long userId = nextUserId();
        Long turnRequestId = aiChatTurnRequestWriter.claim(userId, 7L, "request-f", EXPIRY_TIMEOUT);
        aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.FAILED, "AI_PROVIDER_ERROR");

        // 종료 상태도 행은 남는다 — 같은 식별자의 재전송은 자동 재생성이 아니라 중복으로 거절된다.
        assertThatThrownBy(() -> aiChatTurnRequestWriter.claim(userId, 7L, "request-f", EXPIRY_TIMEOUT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
