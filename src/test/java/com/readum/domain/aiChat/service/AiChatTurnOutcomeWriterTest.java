package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.event.ContextSummarizeTriggerEvent;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatTurnRequest;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatTokenSettlementRepository;
import com.readum.model.aiChat.repository.AiChatTurnRequestRepository;
import com.readum.model.aiChat.repository.UserTokenBudgetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

/**
 * 요청 종료 트랜잭션의 통합 테스트. 트랜잭션 경계 자체가 검증 대상이라 테스트 트랜잭션으로 감싸지 않고
 * (@Transactional 없음) Writer 의 자체 트랜잭션으로 커밋한 뒤 커밋된 상태를 단언한다.
 *
 * <p>여기서 쓰는 DB 는 H2(MySQL 호환 모드)라 행 잠금의 운영 DB 의미까지 재현하지는 못한다 —
 * 동시에 들어온 두 종료 중 하나만 반영된다는 것은 수동 실행 테스트
 * (AiChatTurnOutcomeMySqlRowLockTest)가 MySQL 에 대고 따로 확인한다. 이 클래스가 확인하는 것은
 * "이미 종료된 요청이면 저장·정산·반환을 반복하지 않는다" 는 순서가 정해진 상황의 계약이다.
 */
@SpringBootTest
class AiChatTurnOutcomeWriterTest {

    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");
    private static final Duration EXPIRY_TIMEOUT = Duration.ofMinutes(3);
    private static final long SESSION_ID = 7L;
    private static final int RESERVED_TOKENS = 300;
    private static final int ESTIMATED_MESSAGE_INPUT_TOKENS = 40;

    @Autowired
    private AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter;

    @Autowired
    private AiChatTurnRequestWriter aiChatTurnRequestWriter;

    @Autowired
    private AiChatTurnRequestRepository aiChatTurnRequestRepository;

    @Autowired
    private AiChatMessageRepository aiChatMessageRepository;

    @Autowired
    private AiChatTokenSettlementRepository aiChatTokenSettlementRepository;

    @Autowired
    private UserTokenBudgetRepository userTokenBudgetRepository;

    @Autowired
    private CommittedTurnRecorder committedTurnRecorder;

    /** 정산을 실패시켜 "한 트랜잭션" 여부를 확인하기 위한 spy — 기본 동작은 실제 빈 그대로다. */
    @MockitoSpyBean
    private UserTokenBudgetWriter userTokenBudgetWriter;

    private static long userSeq = 990_000L;

    private static synchronized long nextUserId() {
        return userSeq++;
    }

    @BeforeEach
    void clearRecordedEvents() {
        committedTurnRecorder.clear();
    }

    @AfterEach
    void cleanUp() {
        aiChatMessageRepository.deleteAll();
        aiChatTokenSettlementRepository.deleteAll();
        aiChatTurnRequestRepository.deleteAll();
        userTokenBudgetRepository.deleteAll();
    }

    // ── 성공 확정 ────────────────────────────────────────────────────────

    @Test
    void 성공_확정은_답변_저장과_정산_기록과_예산_보정과_요청_종료를_함께_커밋한다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-success");

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, successfulGeneration("완성된 답변", 1_200, 260), ESTIMATED_MESSAGE_INPUT_TOKENS);

        assertThat(result).isInstanceOf(AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded.class);
        AiChatTurnOutcomeWriter.SavedAssistantMessage assistantMessage =
                ((AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded) result).assistantMessage();

        AiChatMessage saved = aiChatMessageRepository.findById(assistantMessage.messageId()).orElseThrow();
        assertThat(saved.getContent()).isEqualTo("완성된 답변");
        assertThat(saved.getStatus()).isEqualTo(AiChatMessage.Status.COMPLETED);
        assertThat(saved.getOutputTokens()).isEqualTo(260);
        assertThat(aiChatTokenSettlementRepository.existsByMessageId(assistantMessage.messageId())).isTrue();

        AiChatTurnRequest turnRequest = aiChatTurnRequestRepository.findById(turnRequestId).orElseThrow();
        assertThat(turnRequest.getStatus()).isEqualTo(AiChatTurnRequest.Status.SUCCEEDED);
        assertThat(turnRequest.getAssistantMessageId()).isEqualTo(assistantMessage.messageId());
    }

    @Test
    void 성공_확정의_사용자_청구량은_메시지_입력_추정과_실측_출력의_합이다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-charge");

        aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, successfulGeneration("답변", 1_200, 260), ESTIMATED_MESSAGE_INPUT_TOKENS);

        // 공급자 실측 입력(1200, 이전 대화·시스템 메시지 포함)은 청구하지 않는다 — 기존 산식 그대로.
        long chargedTokens = ESTIMATED_MESSAGE_INPUT_TOKENS + 260;
        assertThat(usedTokensOf(userId)).isEqualTo(chargedTokens);
    }

    @Test
    void 정산이_실패하면_답변_저장과_요청_성공_확정이_함께_롤백된다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-settle-fail");
        willThrow(new DataIntegrityViolationException("정산 기록 충돌"))
                .given(userTokenBudgetWriter).settle(anyLong(), anyInt(), anyLong(), anyInt(), anyInt());

        assertThatThrownBy(() -> aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, successfulGeneration("완성된 답변", 1_200, 260), ESTIMATED_MESSAGE_INPUT_TOKENS))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(messagesOf(SESSION_ID)).isEmpty();
        AiChatTurnRequest turnRequest = aiChatTurnRequestRepository.findById(turnRequestId).orElseThrow();
        assertThat(turnRequest.getStatus()).isEqualTo(AiChatTurnRequest.Status.RESERVED);
        assertThat(turnRequest.getAssistantMessageId()).isNull();
        // 예약(R)은 그대로 남는다 — 되돌리는 것은 실패 보상 경로의 몫이다.
        assertThat(usedTokensOf(userId)).isEqualTo(RESERVED_TOKENS);
        assertThat(committedTurnRecorder.recordedSessionIds()).isEmpty();
    }

    @Test
    void 커밋_후_트리거_이벤트는_요청이_성공으로_확정된_뒤에_발행된다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-event");

        aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, successfulGeneration("답변", 1_200, 260), ESTIMATED_MESSAGE_INPUT_TOKENS);

        // 답변 저장이 더 큰 트랜잭션에 합류해도 AFTER_COMMIT 시점은 그대로다 — 다만 그 커밋이
        // 정산·요청 종료까지 포함하므로, 이벤트가 보는 요청은 이미 SUCCEEDED 다.
        assertThat(committedTurnRecorder.recordedSessionIds()).containsExactly(SESSION_ID);
        assertThat(committedTurnRecorder.observedStatuses()).containsExactly(AiChatTurnRequest.Status.SUCCEEDED);
    }

    @Test
    void 정상_완료가_아닌_생성_결과로는_성공을_확정하지_않는다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-not-success");
        AiChatGenerationOutcome failed = new AiChatGenerationOutcome(
                AiChatGenerationOutcome.Status.STREAM_ERROR, "받은 데까지의 조각", null, null, null, null);

        assertThatThrownBy(() -> aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, failed, ESTIMATED_MESSAGE_INPUT_TOKENS))
                .isInstanceOf(IllegalStateException.class);

        assertThat(messagesOf(SESSION_ID)).isEmpty();
    }

    // ── 청구 없는 종료(실패·만료) ─────────────────────────────────────────

    @Test
    void 실패_종료는_부분_본문을_저장하지_않고_예약을_되돌린다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-failed");

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.FAILED, "AI_STREAM_INTERRUPTED");

        assertThat(result).isEqualTo(new AiChatTurnOutcomeWriter.TurnOutcomeResult.FinishedWithoutCharge(
                AiChatTurnRequest.Status.FAILED, RESERVED_TOKENS));
        assertThat(messagesOf(SESSION_ID)).isEmpty();
        assertThat(usedTokensOf(userId)).isZero();
        AiChatTurnRequest turnRequest = aiChatTurnRequestRepository.findById(turnRequestId).orElseThrow();
        assertThat(turnRequest.getStatus()).isEqualTo(AiChatTurnRequest.Status.FAILED);
        assertThat(turnRequest.getFailureCode()).isEqualTo("AI_STREAM_INTERRUPTED");
    }

    @Test
    void 만료_복구는_같은_종료_경로를_사유만_바꿔_쓴다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-expired");

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.EXPIRED, "TURN_EXPIRED");

        assertThat(result).isEqualTo(new AiChatTurnOutcomeWriter.TurnOutcomeResult.FinishedWithoutCharge(
                AiChatTurnRequest.Status.EXPIRED, RESERVED_TOKENS));
        assertThat(usedTokensOf(userId)).isZero();
        assertThat(aiChatTurnRequestRepository.findById(turnRequestId).orElseThrow().getStatus())
                .isEqualTo(AiChatTurnRequest.Status.EXPIRED);
    }

    @Test
    void 실패_보상을_두_번_해도_예약은_한_번만_돌아온다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-double-refund");
        aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.FAILED, "AI_STREAM_INTERRUPTED");

        AiChatTurnOutcomeWriter.TurnOutcomeResult second = aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.FAILED, "AI_PROVIDER_ERROR");

        assertThat(second).isEqualTo(new AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished(
                AiChatTurnRequest.Status.FAILED, null));
        // 두 번째 호출이 다시 되돌렸다면 원장이 음수가 된다.
        assertThat(usedTokensOf(userId)).isZero();
        assertThat(aiChatTurnRequestRepository.findById(turnRequestId).orElseThrow().getFailureCode())
                .isEqualTo("AI_STREAM_INTERRUPTED");
    }

    @Test
    void 예약_전에_끝난_요청은_되돌릴_예약이_없다() {
        long userId = nextUserId();
        Long turnRequestId = aiChatTurnRequestWriter.claim(userId, SESSION_ID, "request-accepted", EXPIRY_TIMEOUT);

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.EXPIRED, "TURN_EXPIRED");

        assertThat(result).isEqualTo(new AiChatTurnOutcomeWriter.TurnOutcomeResult.FinishedWithoutCharge(
                AiChatTurnRequest.Status.EXPIRED, 0));
        assertThat(userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, todayPeriodKey())).isEmpty();
    }

    // ── 성공과 종료의 경쟁 ───────────────────────────────────────────────

    @Test
    void 만료로_끝난_요청에_늦은_성공이_도착해도_답변을_저장하지_않는다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-late-success");
        aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.EXPIRED, "TURN_EXPIRED");

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, successfulGeneration("늦게 도착한 답변", 1_200, 260), ESTIMATED_MESSAGE_INPUT_TOKENS);

        assertThat(result).isEqualTo(new AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished(
                AiChatTurnRequest.Status.EXPIRED, null));
        assertThat(messagesOf(SESSION_ID)).isEmpty();
        // 만료가 되돌린 예약을 성공 정산이 다시 계상하지 않는다.
        assertThat(usedTokensOf(userId)).isZero();
        assertThat(committedTurnRecorder.recordedSessionIds()).isEmpty();
    }

    @Test
    void 성공한_요청에_뒤늦은_실패_보상이_와도_예약을_되돌리지_않는다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-late-failure");
        aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, successfulGeneration("답변", 1_200, 260), ESTIMATED_MESSAGE_INPUT_TOKENS);
        long chargedTokens = ESTIMATED_MESSAGE_INPUT_TOKENS + 260;

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = aiChatTurnOutcomeWriter.finishWithoutCharge(
                turnRequestId, AiChatTurnRequest.Status.FAILED, "AI_STREAM_INTERRUPTED");

        assertThat(result).isInstanceOf(AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished.class);
        assertThat(((AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished) result).status())
                .isEqualTo(AiChatTurnRequest.Status.SUCCEEDED);
        assertThat(usedTokensOf(userId)).isEqualTo(chargedTokens);
    }

    // ── 커밋 결과 재확인 ─────────────────────────────────────────────────

    @Test
    void 커밋_결과가_불확실하면_현재_요청_상태를_다시_읽어_판단한다() {
        long userId = nextUserId();
        Long turnRequestId = reservedTurnRequest(userId, "request-recheck");

        assertThat(aiChatTurnOutcomeWriter.currentOutcome(turnRequestId))
                .isEqualTo(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));

        AiChatTurnOutcomeWriter.TurnOutcomeResult succeeded = aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId, successfulGeneration("답변", 1_200, 260), ESTIMATED_MESSAGE_INPUT_TOKENS);
        Long assistantMessageId = ((AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded) succeeded)
                .assistantMessage().messageId();

        // 커밋 응답만 잃어버린 경우와 정말로 커밋되지 않은 경우를 이 조회로 가른다.
        assertThat(aiChatTurnOutcomeWriter.currentOutcome(turnRequestId))
                .isEqualTo(new AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished(
                        AiChatTurnRequest.Status.SUCCEEDED, assistantMessageId));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────

    private Long reservedTurnRequest(long userId, String requestId) {
        Long turnRequestId = aiChatTurnRequestWriter.claim(userId, SESSION_ID, requestId, EXPIRY_TIMEOUT);
        aiChatTurnRequestWriter.reserveWithRecord(turnRequestId, userId, RESERVED_TOKENS);
        return turnRequestId;
    }

    private static AiChatGenerationOutcome successfulGeneration(
            String content, int inputTokens, int outputTokens) {
        return new AiChatGenerationOutcome(
                AiChatGenerationOutcome.Status.SUCCESS, content, "STOP",
                inputTokens, outputTokens, inputTokens + outputTokens);
    }

    private List<AiChatMessage> messagesOf(long sessionId) {
        return aiChatMessageRepository.findAll().stream()
                .filter(message -> message.getSessionId().equals(sessionId))
                .toList();
    }

    private long usedTokensOf(long userId) {
        return userTokenBudgetRepository.findByUserIdAndPeriodKey(userId, todayPeriodKey())
                .map(ledgerRow -> ledgerRow.getUsedTokens())
                .orElse(0L);
    }

    private static int todayPeriodKey() {
        LocalDate today = LocalDate.now(ZONE_KST);
        return today.getYear() * 10_000 + today.getMonthValue() * 100 + today.getDayOfMonth();
    }

    /**
     * 성공 트랜잭션이 커밋된 뒤에 발행되는 트리거 이벤트를 붙잡아, 그 시점에 요청 행이 이미 종료돼 있는지
     * 확인한다. 커밋 전이라면 여기서 읽은 상태가 RESERVED 로 보인다.
     */
    @TestConfiguration
    static class CommittedTurnRecorderConfig {

        @Bean
        CommittedTurnRecorder committedTurnRecorder(AiChatTurnRequestRepository aiChatTurnRequestRepository) {
            return new CommittedTurnRecorder(aiChatTurnRequestRepository);
        }
    }

    static class CommittedTurnRecorder {

        private final AiChatTurnRequestRepository aiChatTurnRequestRepository;
        private final List<Long> recordedSessionIds = new CopyOnWriteArrayList<>();
        private final List<AiChatTurnRequest.Status> observedStatuses = new CopyOnWriteArrayList<>();

        CommittedTurnRecorder(AiChatTurnRequestRepository aiChatTurnRequestRepository) {
            this.aiChatTurnRequestRepository = aiChatTurnRequestRepository;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void onContextSummarizeTrigger(ContextSummarizeTriggerEvent event) {
            recordedSessionIds.add(event.sessionId());
            aiChatTurnRequestRepository.findAll().stream()
                    .filter(turnRequest -> turnRequest.getSessionId().equals(event.sessionId()))
                    .map(AiChatTurnRequest::getStatus)
                    .forEach(observedStatuses::add);
        }

        void clear() {
            recordedSessionIds.clear();
            observedStatuses.clear();
        }

        List<Long> recordedSessionIds() {
            return List.copyOf(recordedSessionIds);
        }

        List<AiChatTurnRequest.Status> observedStatuses() {
            return List.copyOf(observedStatuses);
        }
    }
}
