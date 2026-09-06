package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 후처리의 <b>종료 트랜잭션만</b> 유계로 다시 시도한다.
 *
 * <p><b>왜 여기만인가.</b> 생성이 끝나 사용자가 답변 전문을 이미 본 뒤에 저장·정산·요청 종료 트랜잭션 하나가
 * 실패하면, 답변은 저장되지 않고 사용자는 {@code error} 를 받아 새 요청 식별자로 처음부터 다시 생성해야 한다.
 * 이 창은 좁고 원인(풀 순간 고갈, 잠금 대기 초과, 일시적 연결 오류)은 대부분 몇 초 안에 지나가므로,
 * 그 몇 초를 다시 시도해 흡수한다. 완성본을 따로 보관하거나 아웃박스를 두지는 않는다 —
 * <b>여기서도 실패하면 답변은 저장되지 않는다</b>.
 *
 * <p><b>다시 시도해도 두 번 반영되지 않는다.</b> 종료 트랜잭션은 이미 멱등이다
 * ({@link AiChatTurnOutcomeWriter} — 요청 행 잠금 → 미종료 확인, 정산 기록의 {@code message_id} UNIQUE).
 * 이미 끝나 있으면 {@link AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished} 가 돌아온다 —
 * 예외가 아니라 정상 결과라 여기서 다시 시도하지 않는다.
 *
 * <p><b>멈추는 조건은 셋이고, 하나만 걸려도 멈춘다.</b>
 * <ol>
 *   <li>시도 횟수 — 최초 1회 + 다시 시도 최대 2회, 합쳐 {@value #MAX_ATTEMPTS} 회.</li>
 *   <li>턴의 만료 시각 — 다음 시도를 시작할 시각이 {@code 진행 목록 등록 시각 + 요청 만료 유예 − 여유} 를
 *       넘으면 시작하지 않는다. 만료 뒤에는 미정산 예약 반환이 같은 행을 닫을 수 있어, 겹쳐 봐야
 *       {@code AlreadyFinished} 가 돌아오는 헛일이 된다(잠금 덕에 두 번 반영되지는 않는다).
 *       시작 시각은 요청 행의 {@code created_at} 이 아니라 진행 목록의 등록 시각을 쓴다 — 몇 ms 이르지만
 *       선행 처리를 시작한 시점이라 안전한 쪽이다.</li>
 *   <li>다시 시도할 예외가 아니면 곧바로 멈춘다.</li>
 * </ol>
 *
 * <p><b>값을 설정으로 빼지 않고 상수로 둔 이유.</b> 이 셋은 조정 손잡이가 아니라 재시도 규칙 자체다.
 * 최악의 소요는 후처리 여유 20초(= 연결 획득 10 + 저장·정산 10)를 넘는다 — 시도 한 번이 Hikari 연결 획득
 * 10초와 행 잠금 대기 5초를 다 쓰면 3회에 45초가 든다. 그래도 여유를 넘겨 도는 일은 없다:
 * 조건 2 가 만료 시각에서 먼저 끊기 때문이다. 그래서 이 상수들은 후처리 여유와 따로 맞춰 둘 값이 아니고,
 * 기동 검증({@code AiChatTimeBudgetValidator})에 더할 조건도 없다.
 *
 * <p>지표는 {@code AiChatPostProcessingMetrics} 가 이 빈의 누적값을 읽어 등록한다 —
 * 도메인 쪽이 Micrometer 를 알지 않게 하려는 분리다(진행 목록 지표와 같은 방식).
 */
@Slf4j
@Component
public class AiChatPostProcessingRetry {

    /** 최초 1회 + 다시 시도 2회. 창이 좁아 간격을 늘려 가며 오래 붙잡을 이유가 없다. */
    static final int MAX_ATTEMPTS = 3;

    /** 다시 시도 사이의 대기. 고정 간격이다 — 흡수하려는 것이 몇 초짜리 순간 경합이라 지수 증가가 필요 없다. */
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);

    /** 만료 시각 앞에 두는 여유. 시도를 시작해 놓고 도중에 만료를 넘겨 미정산 예약 반환과 겹치는 것을 줄인다. */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(2);

    /** 어느 종료를 다시 시도하다 끝내 실패했는지 — 지표 태그 {@code outcome} 의 값이 된다. */
    public enum FinishKind {

        /** 성공 확정(답변 저장 + 정산 + 예산 보정 + 요청 성공 전이). */
        SUCCESS_COMMIT("success_commit"),

        /** 청구 없는 종료(상태 전이 + 예약 반환). */
        WITHOUT_CHARGE("without_charge");

        private final String tagValue;

        FinishKind(String tagValue) {
            this.tagValue = tagValue;
        }

        /** 지표 태그에 실을 값. 이름을 그대로 쓰지 않고 소문자 표기를 못박아 둔다. */
        public String tagValue() {
            return tagValue;
        }
    }

    /** 예외 하나를 보고 정한 다음 행동. */
    private enum RetryDecision {

        /** 트랜잭션이 커밋되지 않은 것이 분명한 일시 실패 — 곧바로 다시 시도한다. */
        RETRY,

        /** 커밋이 반영됐는지 알 수 없다 — 현재 상태를 먼저 읽고, 아직 미종료일 때만 다시 시도한다. */
        RECHECK_THEN_RETRY,

        /** 데이터 오류·도메인 예외·프로그램 오류 — 다시 시도해도 같은 결과다. */
        STOP
    }

    /**
     * 다시 시도 사이의 대기. 후처리 가상 스레드에서 부르므로 {@link Thread#sleep} 이 캐리어 스레드를 점유하지 않는다.
     * 끊기면(interrupt) 신호를 되살린 뒤 {@code false} 를 돌려 그 자리에서 멈추게 한다 —
     * 이 스레드를 끊으려는 쪽이 있다는 뜻이라 더 붙잡고 있지 않는다. 현재 종료 절차는
     * {@code shutdownNow()} 를 쓰지 않으므로({@code AiChatShutdownLifecycle}) 평소에는 이 경로가 돌지 않는다.
     */
    @FunctionalInterface
    interface RetryWaiter {

        boolean await(Duration interval);
    }

    private final Duration turnRequestExpiryTimeout;
    private final InstantSource instantSource;
    private final RetryWaiter retryWaiter;

    /** 다시 시도한 누적 횟수. 지표로만 읽는 값이라 되돌리지 않고 늘어나기만 한다. */
    private final AtomicLong retryCount = new AtomicLong();

    /** 끝내 실패한 성공 확정 수. */
    private final AtomicLong successCommitFailureCount = new AtomicLong();

    /** 끝내 실패한 청구 없는 종료 수. */
    private final AtomicLong withoutChargeFailureCount = new AtomicLong();

    @Autowired
    public AiChatPostProcessingRetry(AiChatProperties aiChatProperties) {
        this(aiChatProperties.streaming().turnRequestExpiryTimeout(),
                InstantSource.system(), AiChatPostProcessingRetry::sleepQuietly);
    }

    /** 시계와 대기를 직접 주는 생성자 — 만료 조건과 횟수 조건을 실제로 기다리지 않고 확인하는 테스트를 위해 연다. */
    AiChatPostProcessingRetry(
            Duration turnRequestExpiryTimeout, InstantSource instantSource, RetryWaiter retryWaiter) {
        this.turnRequestExpiryTimeout = turnRequestExpiryTimeout;
        this.instantSource = instantSource;
        this.retryWaiter = retryWaiter;
    }

    /**
     * 종료 트랜잭션 하나를 유계로 다시 시도한다. 끝내 실패하면 <b>마지막 예외를 그대로 다시 던진다</b> —
     * 멈춘 뒤의 처리는 부르는 쪽의 기존 경로(성공 확정이면 현재 상태 재확인, 청구 없는 종료면 로그 후 미종료로 남김)가
     * 그대로 맡는다.
     *
     * @param finishKind          어느 종료인지 — 지표 태그와 로그에만 쓴다
     * @param turnRequestId       요청 기록 id — 로그에만 쓴다
     * @param turnStartedAt       진행 목록에 이 턴을 올린 시각 ({@code InFlightTurn#startedAt})
     * @param finishCall          종료 트랜잭션 호출
     * @param currentOutcomeReader 커밋 반영 여부가 불확실할 때 현재 상태를 읽는 호출
     *                             ({@link AiChatTurnOutcomeWriter#currentOutcome})
     */
    AiChatTurnOutcomeWriter.TurnOutcomeResult run(
            FinishKind finishKind,
            Long turnRequestId,
            Instant turnStartedAt,
            Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> finishCall,
            Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> currentOutcomeReader
    ) {
        Instant retryDeadline = turnStartedAt.plus(turnRequestExpiryTimeout).minus(EXPIRY_MARGIN);
        RuntimeException lastError;
        int attempt = 1;
        while (true) {
            try {
                return finishCall.get();
            } catch (RuntimeException finishError) {
                lastError = finishError;
                RetryDecision decision = decide(finishError);
                // 멈추는 조건 3(다시 시도할 예외가 아니다)과 1(시도 횟수를 다 썼다).
                if (decision == RetryDecision.STOP || attempt >= MAX_ATTEMPTS) {
                    break;
                }
                if (decision == RetryDecision.RECHECK_THEN_RETRY) {
                    AiChatTurnOutcomeWriter.TurnOutcomeResult finished =
                            finishedByOtherRun(currentOutcomeReader, turnRequestId);
                    if (finished != null) {
                        return finished;
                    }
                }
                // 멈추는 조건 2(만료가 가깝다)와 대기. 대기가 끊기면 그 자리에서 멈춘다.
                if (!waitBeforeRetry(finishKind, turnRequestId, attempt, retryDeadline, finishError)) {
                    break;
                }
                retryCount.incrementAndGet();
                attempt++;
            }
        }
        countFailure(finishKind);
        log.error("채팅 턴 종료 트랜잭션을 {}회 시도하고 끝내 실패했다 — 종료={} turnRequestId={}",
                attempt, finishKind, turnRequestId, lastError);
        throw lastError;
    }

    /** 다시 시도한 누적 횟수. 지표({@code ai_chat_post_processing_retries_total})가 읽는다. */
    public long retryCount() {
        return retryCount.get();
    }

    /** 끝내 실패한 후처리 수. 지표({@code ai_chat_post_processing_failures_total})가 종료별로 읽는다. */
    public long failureCount(FinishKind finishKind) {
        return switch (finishKind) {
            case SUCCESS_COMMIT -> successCommitFailureCount.get();
            case WITHOUT_CHARGE -> withoutChargeFailureCount.get();
        };
    }

    /**
     * 만료가 가까우면 다시 시도하지 않고, 그렇지 않으면 warn 한 줄을 남기고 간격만큼 기다린다.
     *
     * @return 기다림을 마쳐 다시 시도해도 되면 {@code true}, 만료가 가깝거나 기다림이 끊겼으면 {@code false}
     */
    private boolean waitBeforeRetry(
            FinishKind finishKind,
            Long turnRequestId,
            int attempt,
            Instant retryDeadline,
            RuntimeException finishError
    ) {
        Instant now = instantSource.instant();
        Instant nextAttemptAt = now.plus(RETRY_INTERVAL);
        if (nextAttemptAt.isAfter(retryDeadline)) {
            log.warn("만료가 가까워 종료 트랜잭션을 다시 시도하지 않는다 — 종료={} turnRequestId={} "
                            + "시도={}회 다음시도예정={} 재시도마감={} 예외={}",
                    finishKind, turnRequestId, attempt, nextAttemptAt, retryDeadline,
                    finishError.getClass().getSimpleName());
            return false;
        }
        log.warn("종료 트랜잭션 일시 실패 — 다시 시도한다 종료={} turnRequestId={} 시도={}/{} "
                        + "재시도마감까지={}ms 예외={}: {}",
                finishKind, turnRequestId, attempt, MAX_ATTEMPTS,
                Duration.between(now, retryDeadline).toMillis(),
                finishError.getClass().getSimpleName(), finishError.getMessage());
        return retryWaiter.await(RETRY_INTERVAL);
    }

    /**
     * 커밋 반영 여부가 불확실한 예외였을 때, 다시 시도하기 <b>전에</b> 현재 상태를 먼저 읽는다. 이미 종료돼 있으면 커밋이 실제로는
     * 반영된 것이라 다시 시도할 이유가 없고, 다시 시도해 봐야 {@code AlreadyFinished} 만 돌아온다.
     *
     * <p>상태를 읽는 것 자체가 실패하면 알 수 없는 것이므로 <b>다시 시도하는 쪽</b>으로 둔다 —
     * 종료 트랜잭션이 잠금·미종료 확인으로 멱등이라 이미 끝난 요청에 다시 걸어도 덧반영되지 않는다.
     *
     * @return 이미 끝나 있었으면 그 결과, 그 밖에는 {@code null}(계속 다시 시도한다)
     */
    private AiChatTurnOutcomeWriter.TurnOutcomeResult finishedByOtherRun(
            Supplier<AiChatTurnOutcomeWriter.TurnOutcomeResult> currentOutcomeReader,
            Long turnRequestId
    ) {
        try {
            AiChatTurnOutcomeWriter.TurnOutcomeResult currentOutcome = currentOutcomeReader.get();
            if (currentOutcome instanceof AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning) {
                return null;
            }
            log.warn("커밋이 실제로는 반영돼 있었다 — 다시 시도하지 않는다 turnRequestId={} 현재상태={}",
                    turnRequestId, currentOutcome);
            return currentOutcome;
        } catch (RuntimeException recheckError) {
            log.warn("현재 요청 상태를 다시 읽지 못했다 — 다시 시도하는 쪽으로 둔다 turnRequestId={}",
                    turnRequestId, recheckError);
            return null;
        }
    }

    /**
     * 예외 하나를 셋 중 하나로 가른다. 판단 기준은 <b>이 예외가 났을 때 트랜잭션이 커밋됐을 수 있는가</b>다.
     *
     * <ul>
     *   <li>{@link CannotGetJdbcConnectionException} — 연결을 빌리지 못한 것이라 트랜잭션이 시작조차 되지 않았다.
     *       (Spring 계층상 {@link DataAccessResourceFailureException} 의 하위라 그보다 먼저 본다.)</li>
     *   <li>{@link TransientDataAccessException} — 잠금 획득 실패·잠금 대기 초과·질의 기한 초과·일시 연결 오류가
     *       모두 이 아래다({@code CannotAcquireLockException} → {@code PessimisticLockingFailureException} →
     *       {@code ConcurrencyFailureException}, {@code QueryTimeoutException},
     *       {@code TransientDataAccessResourceException}). 문장이 오류로 끝나 트랜잭션이 롤백된다.</li>
     *   <li>{@link RecoverableDataAccessException} — 연결을 되살리면 같은 작업이 성공할 수 있다는 뜻이다.</li>
     *   <li>{@link JpaSystemException} · {@link DataAccessResourceFailureException} ·
     *       {@link TransactionSystemException} — <b>커밋 도중</b> 연결이 끊기면 이 셋 중 하나로 나온다.
     *       커밋이 반영됐는지 예외만 보고는 알 수 없어 현재 상태를 먼저 읽는다.</li>
     *   <li>그 밖(데이터 오류 {@code DataIntegrityViolationException}, 도메인 예외 {@code BusinessException},
     *       프로그램 오류 {@code IllegalStateException} 등) — 다시 시도해도 같은 결과다.</li>
     * </ul>
     */
    private static RetryDecision decide(RuntimeException finishError) {
        if (finishError instanceof CannotGetJdbcConnectionException) {
            return RetryDecision.RETRY;
        }
        if (finishError instanceof TransientDataAccessException
                || finishError instanceof RecoverableDataAccessException) {
            return RetryDecision.RETRY;
        }
        if (finishError instanceof JpaSystemException
                || finishError instanceof DataAccessResourceFailureException
                || finishError instanceof TransactionSystemException) {
            return RetryDecision.RECHECK_THEN_RETRY;
        }
        return RetryDecision.STOP;
    }

    private void countFailure(FinishKind finishKind) {
        switch (finishKind) {
            case SUCCESS_COMMIT -> successCommitFailureCount.incrementAndGet();
            case WITHOUT_CHARGE -> withoutChargeFailureCount.incrementAndGet();
        }
    }

    private static boolean sleepQuietly(Duration interval) {
        try {
            Thread.sleep(interval);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
