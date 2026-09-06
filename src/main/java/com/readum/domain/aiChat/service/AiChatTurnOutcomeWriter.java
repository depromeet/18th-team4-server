package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatTurnRequest;
import com.readum.model.aiChat.repository.AiChatTurnRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 채팅 한 턴을 <b>끝내는</b> DB 트랜잭션 구간. 선언적 {@code @Transactional} 협력자 빈
 * (transaction.md — 외부 호출이 섞인 서비스 흐름에서 원자적 DB 쓰기만 분리).
 *
 * <p>끝내는 방법은 둘뿐이고 둘 다 같은 뼈대다.
 * <ol>
 *   <li><b>같은 요청 행을 잠근다</b> ({@code SELECT ... FOR UPDATE})</li>
 *   <li><b>미종료인지 확인한다</b> — 이미 끝난 요청이면 아무것도 하지 않고 그 상태를 돌려준다</li>
 *   <li>종료에 딸린 DB 반영(답변 저장·정산·예산 보정 또는 예약 반환)과 상태 전이를 <b>한 트랜잭션</b>으로 커밋한다</li>
 * </ol>
 * 잠금 없이 상태만 확인하면 늦은 성공과 만료 복구가 서로를 못 보고 저장·정산·환불을 두 번 반영한다.
 * 정산 기록의 {@code message_id} UNIQUE 는 <b>같은 메시지</b>의 이중 정산만 막는다 — 요청 단위 멱등성은
 * 이 잠금·확인이 만든다(같은 요청이 답변을 두 번 저장하면 메시지 id 가 달라 UNIQUE 로 걸리지 않는다).
 *
 * <p><b>트랜잭션 밖에 두는 것:</b> 외부 API 호출, SSE 쓰기, Redis 전역 게이트 보상. 이 빈은 셋 중 무엇도
 * 하지 않는다 — 커밋 시간을 외부 응답에 매달지 않기 위해서다. 게이트 보상 정책은 이 작업에서 바꾸지 않았다.
 *
 * <p><b>실패 시 부분 본문을 저장하지 않는다</b>(설계 정본 §6.3). 받은 데까지의 조각은 정상 답변이 아니라
 * 사용자에게 완결된 답변으로 보일 위험이 있고, 청구하지 않는 실패에 저장·집계만 남길 이유가 없다.
 * 기존 {@link AiChatMessagePersistService#saveAssistantFailed} 는 이 경로에서 쓰지 않는다.
 *
 * <p><b>후속 작업 접점:</b>
 * <ul>
 *   <li>Task 7(생성 구독·후처리 VT)이 {@link AiChatGenerationOutcome} 판정 결과에 따라
 *       {@link #finishSuccessfully}(성공) 또는 {@link #finishWithoutCharge}(실패, {@code FAILED})를 부른다.
 *       {@link TurnOutcomeResult.Succeeded} 를 받았을 때만 클라이언트에 성공을 알린다 —
 *       {@link TurnOutcomeResult.AlreadyFinished} 는 다른 실행이 이미 끝낸 요청이다.</li>
 *   <li>커밋 응답이 불확실하게 끊겼을 때는 환불로 직행하지 않고 {@link #currentOutcome} 로 현재 상태를
 *       다시 읽어 판단한다.</li>
 *   <li>만료 복구({@link AiChatExpiredTurnRecoveryService})는 {@link #expireIfOverdue} 로 들어온다 —
 *       잠금·미종료 확인·예약 반환은 {@link #finishWithoutCharge} 와 같은 몸통을 쓰고,
 *       잠근 뒤 기한을 다시 확인하는 조건만 더 붙는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AiChatTurnOutcomeWriter {

    /** failure_code 컬럼 길이. 운영 확인용 표식이라 넘치면 잘라 넣는다 — 분기 조건으로 쓰지 않는다. */
    private static final int FAILURE_CODE_MAX_LENGTH = 50;

    private final AiChatTurnRequestRepository aiChatTurnRequestRepository;
    private final AiChatMessagePersistService aiChatMessagePersistService;
    private final UserTokenBudgetWriter userTokenBudgetWriter;

    /**
     * 요청 종료 시도의 결과. 호출자가 <b>이번 호출이 확정했는지</b>와 <b>이미 끝나 있었는지</b>를 구분해
     * 성공을 두 번 알리거나 예약을 두 번 되돌리지 않게 한다.
     */
    sealed interface TurnOutcomeResult {

        /** 이번 호출이 성공으로 확정했다 — 답변 저장·정산·예산 보정이 함께 커밋됐다. */
        record Succeeded(SavedAssistantMessage assistantMessage) implements TurnOutcomeResult {
        }

        /**
         * 이번 호출이 청구 없이 종료했다 — 상태 전이와 예약 반환이 함께 커밋됐다.
         * {@code returnedTokens} 는 실제로 되돌린 예약량이며, 예약 전에 끝난 요청이면 0 이다.
         */
        record FinishedWithoutCharge(AiChatTurnRequest.Status status, int returnedTokens)
                implements TurnOutcomeResult {
        }

        /**
         * 호출 시점에 이미 종료돼 있었다 — 저장·정산·반환을 <b>다시 반영하지 않았다</b>.
         * {@code assistantMessageId} 는 성공으로 끝난 요청에만 있다.
         */
        record AlreadyFinished(AiChatTurnRequest.Status status, Long assistantMessageId)
                implements TurnOutcomeResult {
        }

        /**
         * 아직 종료되지 않았고 이번 호출이 아무것도 바꾸지 않았다 — {@link #currentOutcome} 조회와,
         * 잠그고 보니 아직 기한 전이던 {@link #expireIfOverdue} 에서 나온다.
         */
        record StillRunning(AiChatTurnRequest.Status status) implements TurnOutcomeResult {
        }
    }

    /**
     * 성공 확정으로 저장된 ASSISTANT 메시지. 호출자가 클라이언트에 알릴 값(토큰 수·시각)만 담는다 —
     * 엔티티를 그대로 넘기면 트랜잭션 밖에서 준영속 객체를 만지게 된다.
     */
    record SavedAssistantMessage(
            Long messageId,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 정상 생성 결과를 확정한다 — 답변 저장 + 정산 기록 + 예산 A−R 보정 + 요청 SUCCEEDED 를 한 트랜잭션으로.
     *
     * <p>넷 중 하나라도 실패하면 넷 다 롤백된다. 정산 실패 때문에 답변까지 사라지는 단점은 설계에서
     * 감수하기로 한 것이다(§6.2) — 중간 저장·아웃박스를 두지 않는다.
     *
     * <p><b>사용자 청구량 A 와 공급자 실측 usage 는 다르다.</b> A 는 기존 산식 그대로
     * "메시지 입력 추정 + 실측 출력" 이고, 프롬프트에 실린 이전 대화·시스템 메시지의 입력 토큰은 청구하지 않는다.
     * 반면 메시지 행에는 공급자가 준 usage(입력·출력·합계)를 그대로 남긴다.
     * 실측 출력이 없으면 추정치로 대신하지 않고 프로그램 오류로 본다 — 성공 판정
     * ({@link AiChatGenerationOutcome#isSuccess()})이 유효 사용량을 이미 요구하기 때문이다.
     *
     * @param generation                  {@link AiChatGenerationOutcome#isSuccess()} 인 결과만 받는다
     * @param estimatedMessageInputTokens 예약 때 센 이번 사용자 메시지의 입력 추정 토큰 (청구량 A 의 입력 몫)
     */
    @Transactional
    TurnOutcomeResult finishSuccessfully(
            Long turnRequestId, AiChatGenerationOutcome generation, int estimatedMessageInputTokens) {
        if (generation == null || !generation.isSuccess()) {
            // 실패한 생성이 성공 경로로 들어오면 부분 본문이 정상 답변으로 저장된다 — 도달하면 안 되는 분기다.
            throw new IllegalStateException(
                    "성공 확정에 정상 완료가 아닌 생성 결과가 들어왔습니다: turnRequestId=" + turnRequestId
                            + " status=" + (generation == null ? "null" : generation.status()));
        }
        AiChatTurnRequest turnRequest = lock(turnRequestId);
        if (turnRequest.isTerminal()) {
            log.warn("이미 종료된 요청의 성공 확정 생략 turnRequestId={} status={}",
                    turnRequestId, turnRequest.getStatus());
            return new TurnOutcomeResult.AlreadyFinished(
                    turnRequest.getStatus(), turnRequest.getAssistantMessageId());
        }
        if (!turnRequest.hasReservation()) {
            // 예약 없이 생성이 돌았다는 뜻이라 정산 좌표(예산 기간)도 되돌릴 양도 없다.
            throw new IllegalStateException(
                    "예약 정보 없는 요청의 성공 확정: turnRequestId=" + turnRequestId
                            + " status=" + turnRequest.getStatus());
        }

        AiChatMessage saved = aiChatMessagePersistService.saveAssistantSuccess(
                turnRequest.getSessionId(), generation.content(), toCompletion(generation));
        SavedAssistantMessage assistantMessage = new SavedAssistantMessage(
                saved.getId(), saved.getInputTokens(), saved.getOutputTokens(),
                saved.getTotalTokens(), saved.getCreatedAt());

        // 상태 전이를 정산보다 먼저 해 둔다: 정산의 보정 UPDATE 는 @Modifying(flushAutomatically, clearAutomatically)
        // 라 실행 전에 이 변경을 flush 하고 실행 뒤 영속성 컨텍스트를 비운다. 순서를 뒤집으면 전이가
        // 준영속 객체 위에서 일어나 커밋에 실리지 않는다.
        turnRequest.markSucceeded(assistantMessage.messageId());
        userTokenBudgetWriter.settle(
                turnRequest.getUserId(),
                turnRequest.getBudgetPeriodKey(),
                assistantMessage.messageId(),
                turnRequest.getReservedTokens(),
                chargedTokens(generation, estimatedMessageInputTokens));
        return new TurnOutcomeResult.Succeeded(assistantMessage);
    }

    /**
     * 사용자에게 청구하지 않고 요청을 끝낸다 — 상태 전이 + 예약 반환(0−R)을 한 트랜잭션으로.
     * <b>ASSISTANT 부분 본문은 저장하지 않는다.</b>
     *
     * <p>사유만 바꿔 두 곳에서 쓴다: 생성·후처리 실패는 {@code FAILED}, 기한이 지난 요청을 정리하는
     * 만료 복구는 {@code EXPIRED} 다. 잠금·미종료 확인·반환 규칙은 둘이 같아야 늦은 성공과 복구가
     * 서로를 덮어쓰지 않는다.
     *
     * @param terminalStatus {@code FAILED} 또는 {@code EXPIRED}
     * @param failureCode    운영 확인용 사유 표식(ErrorCode 이름·판정 상태 이름 등)
     */
    @Transactional
    TurnOutcomeResult finishWithoutCharge(
            Long turnRequestId, AiChatTurnRequest.Status terminalStatus, String failureCode) {
        AiChatTurnRequest turnRequest = lock(turnRequestId);
        if (turnRequest.isTerminal()) {
            log.warn("이미 종료된 요청의 실패 보상 생략 turnRequestId={} status={} 요청한 종료={}",
                    turnRequestId, turnRequest.getStatus(), terminalStatus);
            return new TurnOutcomeResult.AlreadyFinished(
                    turnRequest.getStatus(), turnRequest.getAssistantMessageId());
        }
        return finishLockedWithoutCharge(turnRequest, terminalStatus, failureCode);
    }

    /**
     * 기한이 지난 요청을 만료로 끝낸다 — 만료 복구가 쓰는 입구다.
     * {@link #finishWithoutCharge} 와 <b>같은 잠금·미종료 확인·반환 규칙</b>을 쓰되, 조건 하나를 더 본다:
     * 잠근 행이 정말 기한을 넘겼는지 다시 확인한다.
     *
     * <p>잠근 뒤에 다시 보는 이유는 두 가지다.
     * <ul>
     *   <li>대상 목록을 훑은 시점과 이 행을 잠근 시점 사이에 다른 실행이 이 행을 끝냈을 수 있다
     *       (미종료 확인이 걸러 낸다).</li>
     *   <li>훑을 때의 판단을 그대로 믿고 상태를 바꾸지 않는다 — 판정 근거인 기한을 잠근 행에서 다시 읽는다.
     *       기한 전이면 아무것도 하지 않고 {@link TurnOutcomeResult.StillRunning} 을 돌려준다.
     *       정상적으로 후처리에 들어간 요청을 복구가 가로채 환불하지 않게 하는 마지막 방어다.</li>
     * </ul>
     *
     * <p>생성을 다시 실행하지 않는다. 만료는 "이 요청을 더 기다리지 않고 예약을 사용자에게 돌려준다" 는
     * 정산 결정일 뿐이고, 답변을 되살리는 일은 사용자의 새 요청(새 requestId)이 한다.
     *
     * @param overdueBefore 이 시각보다 기한이 앞선 행만 만료로 확정한다 — 대상 목록을 훑을 때 쓴 기준과 같아야 한다
     */
    @Transactional
    TurnOutcomeResult expireIfOverdue(Long turnRequestId, LocalDateTime overdueBefore, String failureCode) {
        AiChatTurnRequest turnRequest = lock(turnRequestId);
        if (turnRequest.isTerminal()) {
            // 정상이다 — 늦은 성공이나 다른 복구 실행이 먼저 끝냈다. 저장·정산·반환을 다시 반영하지 않는다.
            log.info("이미 종료된 요청의 만료 복구 생략 turnRequestId={} status={}",
                    turnRequestId, turnRequest.getStatus());
            return new TurnOutcomeResult.AlreadyFinished(
                    turnRequest.getStatus(), turnRequest.getAssistantMessageId());
        }
        if (!turnRequest.isOverdueAt(overdueBefore)) {
            log.info("아직 기한 전인 요청의 만료 복구 생략 turnRequestId={} expiresAt={} 기준={}",
                    turnRequestId, turnRequest.getExpiresAt(), overdueBefore);
            return new TurnOutcomeResult.StillRunning(turnRequest.getStatus());
        }
        return finishLockedWithoutCharge(turnRequest, AiChatTurnRequest.Status.EXPIRED, failureCode);
    }

    /**
     * 잠그고 미종료임을 확인한 행을 청구 없이 끝낸다 — 실패 보상과 만료 복구가 공유하는 몸통이다.
     * 되돌릴 양은 호출자가 기억한 값이 아니라 잠근 행에 적힌 값을 쓴다 — 만료 복구처럼 예약 당시의
     * 실행이 이미 사라진 경우에도 같은 규칙으로 정확히 예약한 만큼만 반환하기 위해서다.
     */
    private TurnOutcomeResult finishLockedWithoutCharge(
            AiChatTurnRequest turnRequest, AiChatTurnRequest.Status terminalStatus, String failureCode) {
        int returnedTokens = 0;
        if (turnRequest.hasReservation()) {
            returnedTokens = turnRequest.getReservedTokens();
        }
        markTerminal(turnRequest, terminalStatus, failureCode);
        if (returnedTokens > 0) {
            userTokenBudgetWriter.refund(
                    turnRequest.getUserId(), turnRequest.getBudgetPeriodKey(), returnedTokens);
        }
        return new TurnOutcomeResult.FinishedWithoutCharge(terminalStatus, returnedTokens);
    }

    /**
     * 현재 요청 상태를 다시 읽는다 — 커밋 응답이 불확실하게 끊겼을 때(예: 커밋 도중 연결 오류) 쓴다.
     * 커밋이 실제로 반영됐는지는 애플리케이션이 받은 예외로 알 수 없으므로, 예외만 보고 환불로 직행하면
     * 성공 커밋된 요청의 예약까지 되돌릴 수 있다. 잠그지 않고 아무것도 바꾸지 않는다.
     */
    TurnOutcomeResult currentOutcome(Long turnRequestId) {
        AiChatTurnRequest turnRequest = aiChatTurnRequestRepository.findById(turnRequestId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.TURN_REQUEST_NOT_FOUND));
        if (!turnRequest.isTerminal()) {
            return new TurnOutcomeResult.StillRunning(turnRequest.getStatus());
        }
        return new TurnOutcomeResult.AlreadyFinished(
                turnRequest.getStatus(), turnRequest.getAssistantMessageId());
    }

    private AiChatTurnRequest lock(Long turnRequestId) {
        return aiChatTurnRequestRepository.findByIdForUpdate(turnRequestId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.TURN_REQUEST_NOT_FOUND));
    }

    private void markTerminal(
            AiChatTurnRequest turnRequest, AiChatTurnRequest.Status terminalStatus, String failureCode) {
        String storedFailureCode = trimFailureCode(failureCode);
        switch (terminalStatus) {
            case FAILED -> turnRequest.markFailed(storedFailureCode);
            case EXPIRED -> turnRequest.markExpired(storedFailureCode);
            default -> throw new IllegalStateException(
                    "청구 없는 종료에 쓸 수 없는 상태입니다: " + terminalStatus);
        }
    }

    private static String trimFailureCode(String failureCode) {
        if (failureCode == null || failureCode.length() <= FAILURE_CODE_MAX_LENGTH) {
            return failureCode;
        }
        return failureCode.substring(0, FAILURE_CODE_MAX_LENGTH);
    }

    /** 메시지 행에 남길 값 — 공급자가 준 usage 를 그대로 옮긴다(청구량 A 와는 다른 축이다). */
    private static AiChatCompletion toCompletion(AiChatGenerationOutcome generation) {
        return new AiChatCompletion(
                generation.content(),
                generation.inputTokens(),
                generation.outputTokens(),
                generation.totalTokens(),
                null);
    }

    /** 사용자 청구량 A = 이번 메시지 입력 추정 + 실측 출력 (기존 산식 유지). */
    private static int chargedTokens(AiChatGenerationOutcome generation, int estimatedMessageInputTokens) {
        Integer outputTokens = generation.outputTokens();
        if (outputTokens == null) {
            // 성공 판정이 유효 사용량을 요구하므로 도달할 수 없다. 추정치로 대신하지 않는다.
            throw new IllegalStateException("정상 완료로 판정된 생성 결과에 실측 출력 토큰이 없습니다.");
        }
        return estimatedMessageInputTokens + outputTokens;
    }
}
