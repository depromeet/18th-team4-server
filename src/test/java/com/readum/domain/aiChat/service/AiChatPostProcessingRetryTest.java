package com.readum.domain.aiChat.service;

import com.readum.model.aiChat.entity.AiChatTurnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 후처리 종료 트랜잭션의 유계 재시도. 확인하려는 것은 <b>무엇을 다시 시도하고 무엇을 멈추는가</b> 하나다 —
 * 사용자가 답변 전문을 본 뒤의 저장이 일시 실패로 날아가지 않게 하되, 데이터 오류를 세 번 반복하거나
 * 만료 뒤까지 붙잡고 있지 않아야 한다.
 *
 * <p>실제로 기다리지 않는다: 시계와 대기를 직접 주는 생성자를 쓴다.
 */
class AiChatPostProcessingRetryTest {

    /** 한 턴의 요청 만료 유예 — 운영 후보값 50초(선행 10 + 생성 20 + 후처리 20). */
    private static final Duration EXPIRY_TIMEOUT = Duration.ofSeconds(50);

    private static final Instant TURN_STARTED_AT = Instant.parse("2026-09-06T12:00:00Z");
    private static final Long TURN_REQUEST_ID = 4242L;

    private static final AiChatTurnOutcomeWriter.TurnOutcomeResult SUCCEEDED =
            new AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded(
                    new AiChatTurnOutcomeWriter.SavedAssistantMessage(1L, 10, 5, 15, null));

    /** 기다린 횟수만 세고 곧바로 돌아오는 대역. */
    private final List<Duration> waitedIntervals = new ArrayList<>();

    private int currentOutcomeReadCount = 0;

    @Test
    void 잠금_대기_초과로_한_번_실패하면_다시_시도해_확정한다() {
        AiChatPostProcessingRetry retry = retryAt(TURN_STARTED_AT);
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(
                new CannotAcquireLockException("Lock wait timeout exceeded"), SUCCEEDED);

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = retry.run(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, stillRunning());

        assertThat(result).isEqualTo(SUCCEEDED);
        assertThat(retry.retryCount()).isEqualTo(1);
        assertThat(retry.failureCount(AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT)).isZero();
        assertThat(waitedIntervals).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void 연결_획득_실패와_질의_기한_초과도_다시_시도한다() {
        AiChatPostProcessingRetry retry = retryAt(TURN_STARTED_AT);
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(
                new CannotGetJdbcConnectionException("풀이 순간 고갈됐다"),
                new QueryTimeoutException("질의가 기한을 넘겼다"),
                SUCCEEDED);

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = retry.run(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, stillRunning());

        assertThat(result).isEqualTo(SUCCEEDED);
        assertThat(retry.retryCount()).isEqualTo(2);
        // 커밋 반영 여부가 불확실한 예외가 아니라 현재 상태를 다시 읽지 않았다.
        assertThat(currentOutcomeReadCount).isZero();
    }

    @Test
    void 데이터_오류는_다시_시도하지_않고_그대로_던진다() {
        AiChatPostProcessingRetry retry = retryAt(TURN_STARTED_AT);
        DataIntegrityViolationException dataError = new DataIntegrityViolationException("중복 키");
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(dataError, SUCCEEDED);

        assertThatThrownBy(() -> retry.run(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, stillRunning()))
                .isSameAs(dataError);

        assertThat(retry.retryCount()).isZero();
        assertThat(waitedIntervals).isEmpty();
        assertThat(retry.failureCount(AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT)).isEqualTo(1);
    }

    @Test
    void 세_번_다_실패하면_마지막_예외를_던지고_끝내_실패로_센다() {
        AiChatPostProcessingRetry retry = retryAt(TURN_STARTED_AT);
        CannotAcquireLockException lastError = new CannotAcquireLockException("세 번째");
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(
                new CannotAcquireLockException("첫 번째"),
                new CannotAcquireLockException("두 번째"),
                lastError,
                SUCCEEDED);

        assertThatThrownBy(() -> retry.run(
                AiChatPostProcessingRetry.FinishKind.WITHOUT_CHARGE,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, stillRunning()))
                .isSameAs(lastError);

        // 최초 1회 + 다시 시도 2회에서 멈춘다 — 네 번째 호출(성공)에는 닿지 않는다.
        assertThat(retry.retryCount()).isEqualTo(2);
        assertThat(retry.failureCount(AiChatPostProcessingRetry.FinishKind.WITHOUT_CHARGE)).isEqualTo(1);
        assertThat(retry.failureCount(AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT)).isZero();
    }

    @Test
    void 만료_여유를_넘긴_시점이면_다시_시도하지_않는다() {
        // 지금이 등록 시각 + 48초 — 다음 시도 시작 시각(+49초)이 재시도 마감(+48초)을 넘는다.
        AiChatPostProcessingRetry retry = retryAt(TURN_STARTED_AT.plusSeconds(48));
        CannotAcquireLockException lockError = new CannotAcquireLockException("잠금 대기 초과");
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(lockError, SUCCEEDED);

        assertThatThrownBy(() -> retry.run(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, stillRunning()))
                .isSameAs(lockError);

        assertThat(retry.retryCount()).isZero();
        assertThat(waitedIntervals).isEmpty();
    }

    @Test
    void 커밋이_불확실한_예외는_현재_상태를_먼저_읽고_이미_종료돼_있으면_다시_시도하지_않는다() {
        AiChatPostProcessingRetry retry = retryAt(TURN_STARTED_AT);
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(
                new JpaSystemException(new RuntimeException("커밋 도중 연결이 끊겼다")), SUCCEEDED);
        AiChatTurnOutcomeWriter.TurnOutcomeResult alreadyFinished =
                new AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished(
                        AiChatTurnRequest.Status.SUCCEEDED, 42L);

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = retry.run(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, () -> {
                    currentOutcomeReadCount++;
                    return alreadyFinished;
                });

        assertThat(result).isEqualTo(alreadyFinished);
        assertThat(currentOutcomeReadCount).isEqualTo(1);
        assertThat(retry.retryCount()).isZero();
        assertThat(waitedIntervals).isEmpty();
        assertThat(retry.failureCount(AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT)).isZero();
    }

    @Test
    void 커밋이_불확실해도_아직_미종료면_다시_시도한다() {
        AiChatPostProcessingRetry retry = retryAt(TURN_STARTED_AT);
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(
                new JpaSystemException(new RuntimeException("커밋 도중 연결이 끊겼다")), SUCCEEDED);

        AiChatTurnOutcomeWriter.TurnOutcomeResult result = retry.run(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, stillRunning());

        assertThat(result).isEqualTo(SUCCEEDED);
        assertThat(currentOutcomeReadCount).isEqualTo(1);
        assertThat(retry.retryCount()).isEqualTo(1);
    }

    @Test
    void 기다림이_끊기면_그_자리에서_멈춘다() {
        AiChatPostProcessingRetry retry = new AiChatPostProcessingRetry(
                EXPIRY_TIMEOUT, InstantSource.fixed(TURN_STARTED_AT), interval -> false);
        CannotAcquireLockException lockError = new CannotAcquireLockException("잠금 대기 초과");
        Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finish = finishThat(lockError, SUCCEEDED);

        assertThatThrownBy(() -> retry.run(
                AiChatPostProcessingRetry.FinishKind.WITHOUT_CHARGE,
                TURN_REQUEST_ID, TURN_STARTED_AT, finish, stillRunning()))
                .isSameAs(lockError);

        assertThat(retry.retryCount()).isZero();
    }

    private AiChatPostProcessingRetry retryAt(Instant now) {
        return new AiChatPostProcessingRetry(EXPIRY_TIMEOUT, InstantSource.fixed(now), interval -> {
            waitedIntervals.add(interval);
            return true;
        });
    }

    /** 아직 미종료라고 답하는 현재 상태 조회 — 읽은 횟수를 센다. */
    private Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> stillRunning() {
        return () -> {
            currentOutcomeReadCount++;
            return new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                    AiChatTurnRequest.Status.RESERVED);
        };
    }

    /**
     * 정해진 순서대로 응답하는 종료 트랜잭션 대역. 예외는 던지고, 결과는 돌려준다.
     * 준비한 응답을 다 쓰면 마지막 응답을 되풀이한다.
     */
    private Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finishThat(Object... responses) {
        Deque<Object> remaining = new ArrayDeque<>(List.of(responses));
        return () -> {
            Object response = remaining.size() > 1 ? remaining.poll() : remaining.peek();
            if (response instanceof RuntimeException failure) {
                throw failure;
            }
            return (AiChatTurnOutcomeWriter.TurnOutcomeResult) response;
        };
    }
}
