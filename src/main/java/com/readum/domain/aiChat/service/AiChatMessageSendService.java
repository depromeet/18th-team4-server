package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.aiChat.out.UserMessageRateLimiter;
import com.readum.domain.aiChat.stream.ChatDeliveryChannel;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatTurnRequest;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatMessageSendService {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\p{Z}\\s]+");

    private final AiChatMessagePersistService aiChatMessagePersistService;
    private final AiChatClient aiChatClient;
    private final AiChatProperties aiChatProperties;
    private final UserMessageRateLimiter userMessageRateLimiter;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;
    private final InputModerationClient inputModerationClient;
    private final UserTokenBudgetWriter userTokenBudgetWriter;
    private final AiChatTurnRequestWriter aiChatTurnRequestWriter;
    private final AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter;
    private final AiChatInFlightTurnRegistry aiChatInFlightTurnRegistry;
    private final ExecutorService aiChatPostProcessingExecutor;
    private final TokenCounter tokenCounter;

    /**
     * 선행 처리 결과 — 생성 단계(generateAndDeliver)에 필요한 모든 문맥. 예약(reservation)은 항상 존재한다.
     * rateLimitPermit 은 전역 게이트 확보 결과로 항상 존재한다 (게이트가 검사 없이 통과시켰다면 계상 없는 permit).
     * turnRequestId 는 이 턴의 요청 기록(ai_chat_turn_request) id — 생성 이후의 요청 종료(성공 확정·실패
     * 기록·예약 반환)가 잠글 행을 가리킨다.
     * inFlightTurn 은 진행 목록에 올린 이 실행의 자리 — 후처리가 끝나면 이 자리를 정리한다.
     */
    public record PreparedChatTurn(
            Long userId,
            Long turnRequestId,
            AiChatInFlightTurnRegistry.InFlightTurn inFlightTurn,
            AiChatStreamCommand streamCommand,
            UserTokenBudgetWriter.ReserveResult.Granted reservation,
            int estimatedMessageInputTokens,
            AiChatClient.RateLimitPermit rateLimitPermit
    ) {
        public Long sessionId() {
            return streamCommand.conversationId();
        }
    }

    /**
     * 선행 처리(요청의 가상 스레드에서 동기 순차): <b>진행 목록 등록</b> → 본문 검증 → <b>요청 중복 판정</b> →
     * 사용자 폭주 가드 → 예산 예약 → 이력 조회 → 입력 모더레이션 → 전역 게이트 → USER 저장.
     * 각 단계는 앞 단계의 결과를 보고 다음을 정하는 순차 업무이고, 요청 스레드가 가상 스레드라
     * 여기서 기다려도 다른 요청의 처리를 막지 않는다 — 그래서 리액티브 체인 밖에 둔다.
     * 여기서 던진 예외는 SSE 시작 전이라 GlobalExceptionHandler 가 4xx/5xx JSON 으로 변환한다.
     *
     * <p>중복 판정을 폭주 가드보다 앞에 두는 이유: 재전송은 사용자가 새로 시도한 것이 아니라 같은 요청이
     * 다시 도착한 것이다. 가드를 먼저 통과시키면 재전송이 폭주 슬롯을 갉아먹고(슬롯은 반환하지 않는다),
     * 예약·moderation 같은 부수 효과도 중복으로 시작된다. 중복 판정 자체도 DB 삽입이라 부수 효과지만,
     * 그 삽입이 곧 멱등 판정이라 어떤 것보다 앞서야 한다.
     *
     * <p>예산 예약을 moderation 앞에 두는 이유: 예산이 소진된 사용자가 공짜 moderation 호출
     * (제공자 RPM 자원)을 소모하지 못하게 한다 — 토큰 추정(tokenCounter)은 로컬 계산이라
     * 예약을 앞으로 당겨도 외부 비용이 없다.
     *
     * <p>진행 목록 등록을 맨 앞에 두는 이유: 등록은 이 실행을 종료 대기의 추적 대상으로 올리는 일이라
     * <b>첫 부수 효과(요청 자리 삽입)보다 앞서야</b> 예약·외부 호출을 시작해 놓고 추적에서 빠지는 턴이 없다.
     * 종료 절차가 이미 시작됐다면 등록이 503 으로 거절되고, 그 뒤로는 아무 부수 효과도 일어나지 않는다.
     * 선행 처리가 거절로 끝나면 이 호출의 등록도 함께 정리한다 — 중복(409)으로 거절된 재전송도 마찬가지다.
     */
    public PreparedChatTurn prepare(SendMessageCommand command) {
        Long sessionId = command.sessionId();
        Long userId = command.userId();

        AiChatInFlightTurnRegistry.InFlightTurn inFlightTurn =
                aiChatInFlightTurnRegistry.register(command.requestId(), sessionId, userId);
        try {
            return prepareRegisteredTurn(command, inFlightTurn);
        } catch (RuntimeException prepareRejection) {
            // 이 호출은 여기서 끝난다 — 생성·후처리가 없으므로 진행 목록의 자리를 지금 정리한다.
            aiChatInFlightTurnRegistry.finish(inFlightTurn);
            throw prepareRejection;
        }
    }

    private PreparedChatTurn prepareRegisteredTurn(
            SendMessageCommand command, AiChatInFlightTurnRegistry.InFlightTurn inFlightTurn) {
        String normalizedContent = validateAndStripContent(command.content());
        Long sessionId = command.sessionId();
        Long userId = command.userId();

        Long turnRequestId = claimTurnRequest(userId, sessionId, command.requestId());

        // 예약 전 거절(폭주 가드·예산 거절)과 예약 후 거절(이력 조회·moderation·게이트·USER 저장)을 나눈다.
        // 바깥 catch 는 두 경우 모두 요청 상태를 실패로 끝내고, 안쪽 catch 는 예약이 있는 경우에만 환불한다.
        try {
            verifyUserMessageRateLimit(userId);

            BudgetReservation reservation = reserveTokenBudget(turnRequestId, userId, normalizedContent);

            try {
                return prepareAfterReservation(
                        sessionId, userId, normalizedContent, turnRequestId, inFlightTurn, reservation);
            } catch (RuntimeException rejectionAfterReservation) {
                refundQuietly(userId, reservation.granted(), sessionId);
                throw rejectionAfterReservation;
            }
        } catch (RuntimeException prepareRejection) {
            failTurnRequestQuietly(turnRequestId, prepareRejection);
            throw prepareRejection;
        }
    }

    /**
     * 요청 자리를 잡으며 중복을 판정한다 — 판정은 조회가 아니라 (userId, requestId) UNIQUE 삽입이라
     * 같은 ID 의 동시 요청 중 정확히 한 건만 통과한다.
     * 중복이면 409 로 거절한다: 진행 중이든 이미 끝났든(성공·실패) 새 생성·예약·과금을 시작하지 않는다.
     * 실패한 요청을 다시 시도하려면 클라이언트가 새 식별자를 보낸다.
     * 같은 ID 에 다른 본문이 실려 와도 본문을 비교하지 않고 같은 요청으로 보아 409 로 거절한다
     * — 클라이언트 계약의 <b>후보</b>이며, 확정 전까지는 이 단순한 규칙을 쓴다.
     */
    private Long claimTurnRequest(Long userId, Long sessionId, String requestId) {
        try {
            return aiChatTurnRequestWriter.claim(userId, sessionId, requestId, turnRequestExpiryTimeout());
        } catch (DataIntegrityViolationException duplicateRequest) {
            log.info("중복 요청 거절 userId={} sessionId={} requestId={}", userId, sessionId, requestId);
            throw new ConflictException(AiChatErrorCode.DUPLICATE_TURN_REQUEST);
        }
    }

    /**
     * 만료 유예 = 생성 전체 기한 + 종료 대기 상한. 생성이 전체 기한까지 늘어지고 그 뒤 후처리가
     * 종료 대기 상한만큼 더 걸려도 정상 요청을 만료로 오판하지 않는 하한이다.
     * <b>후보값</b>이며, 만료 시각과 후처리 대기 정책의 관계는 만료 복구 작업에서 확정한다.
     */
    private Duration turnRequestExpiryTimeout() {
        AiChatProperties.Streaming streaming = aiChatProperties.streaming();
        return Duration.ofSeconds(
                (long) streaming.generationTotalTimeoutSeconds() + streaming.shutdownWaitSeconds());
    }

    /**
     * 선행 단계의 거절로 요청 상태를 끝낸다. 실패는 삼킨다 — 여기서 던지면 원래의 4xx 가 500 으로 둔갑한다.
     * 끝내지 못한 행은 미종료로 남아 만료 복구의 대상이 된다.
     */
    private void failTurnRequestQuietly(Long turnRequestId, RuntimeException prepareRejection) {
        try {
            aiChatTurnRequestWriter.markFailed(turnRequestId, failureCodeOf(prepareRejection));
        } catch (RuntimeException failRecordError) {
            log.error("요청 실패 기록 실패 turnRequestId={}", turnRequestId, failRecordError);
        }
    }

    /** 실패 사유 표식 — 도메인 예외면 ErrorCode 이름, 아니면 예외 타입 이름(운영 확인용, 컬럼 길이에 맞춰 자름). */
    private String failureCodeOf(RuntimeException prepareRejection) {
        String code = (prepareRejection instanceof BusinessException businessException)
                ? businessException.getErrorCode().name()
                : prepareRejection.getClass().getSimpleName();
        return code.length() > 50 ? code.substring(0, 50) : code;
    }

    private PreparedChatTurn prepareAfterReservation(
            Long sessionId,
            Long userId,
            String normalizedContent,
            Long turnRequestId,
            AiChatInFlightTurnRegistry.InFlightTurn inFlightTurn,
            BudgetReservation reservation
    ) {
        TurnContext turnContext = loadTurnContext(sessionId, userId);

        InputModerationResult moderation =
                inputModerationClient.check(normalizedContent, turnContext.bookContext());
        applyModerationDecision(moderation, sessionId, userId, normalizedContent);

        AiChatStreamCommand streamCommand = buildStreamCommand(sessionId, normalizedContent, turnContext);

        // 전역 게이트: SSE 시작 전에 확보한다. USER 저장보다 먼저 확인해
        // 거절(429) 시 응답 없는 USER 메시지가 대화 이력에 남지 않게 한다.
        // 거절 시 예약 환불은 prepare() 의 공통 환불 경로가 담당하고, 게이트 분당 계상은
        // tryAcquire 가 거절하면서 스스로 되돌렸으므로 여기서 또 보상하면 이중 차감이다.
        AiChatClient.RateLimitPermit rateLimitPermit = aiChatClient.acquireRateLimitPermit(streamCommand);

        recordUserMessageOrCompensate(sessionId, userId, normalizedContent, rateLimitPermit);

        return new PreparedChatTurn(
                userId,
                turnRequestId,
                inFlightTurn,
                streamCommand,
                reservation.granted(),
                reservation.estimatedMessageInputTokens(),
                rateLimitPermit);
    }

    /**
     * 생성 단계: <b>서버가 구독을 소유한다</b>. 이 메서드는 구독만 걸고 곧바로 돌아온다 —
     * 요청 스레드는 여기서 생성이 끝나기를 기다리지 않고 {@code SseEmitter} 를 돌려주러 간다.
     *
     * <p>청크 콜백이 하는 일은 셋뿐이다: 누적, 메타데이터·사용량 수집({@link AiChatGenerationAccumulator}),
     * 전달 큐 입력 시도({@link ChatDeliveryChannel#offerDelta}). <b>큐 빈자리도 SSE 쓰기도 기다리지 않는다.</b>
     * 그래서 이 경로에는 블로킹 작업이 없고, 예전처럼 청크마다 공유 풀로 넘기는 실행 경계
     * ({@code publishOn(boundedElastic)})도 두지 않는다.
     *
     * <p><b>연결 종료·큐 포화·전달 실패를 상류 취소로 연결하지 않는다.</b> 전달이 끝나도 채널은
     * 예외 없이 무시할 뿐이고 구독은 그대로 남아 생성을 끝까지 소비한다. 취소로 이으면 저장·정산이
     * 통째로 건너뛰어져 예약만 남는다.
     *
     * <p><b>기한:</b> 전체 생성 기한은 이 구독(= 외부 호출 시작)부터, 무응답 기한은 스트림 시작 또는
     * 마지막 인정 수신부터 잰다. 어느 쪽이든 기한에 걸리면 상류 구독을 취소하고 생성 실패로 판정한다
     * ({@link AiChatGenerationAccumulator#failWithTimeout()}). 반면 <b>저장·정산은 이 체인 밖의
     * 후처리 VT 에서 돌아</b> 생성 타이머의 영향을 받지 않는다.
     *
     * <p><b>후처리로의 안전한 전달:</b> 누적기는 한 시퀀스의 신호가 직렬로 전달된다는 Reactor 의 전제
     * 위에서 동기화 없이 누적한다. 종료 신호(onComplete/onError)는 그 시퀀스에서 마지막에 오므로 그 시점의
     * 누적 상태는 완결돼 있고, {@code ExecutorService#execute} 제출은 제출 전 동작이 실행 스레드의 동작보다
     * 앞선다고 보장하므로(happens-before) 후처리 VT 는 그 완결된 상태를 그대로 본다.
     */
    public void generateAndDeliver(PreparedChatTurn turn, ChatDeliveryChannel deliveryChannel) {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        // 종료 훅은 서로 배타적이지만(onComplete/onError 중 하나), 구독 실패까지 겹칠 수 있어
        // 후처리 제출은 한 번만 일어나도록 잠근다 — 두 번 제출하면 저장·정산 트랜잭션이 두 번 돈다.
        AtomicBoolean postProcessingSubmitted = new AtomicBoolean(false);
        try {
            generationStream(turn).subscribe(
                    chunk -> receiveChunk(chunk, accumulator, deliveryChannel),
                    generationError -> submitPostProcessing(
                            turn, deliveryChannel, postProcessingSubmitted,
                            judgeFailedStream(accumulator, generationError), generationError),
                    () -> submitPostProcessing(
                            turn, deliveryChannel, postProcessingSubmitted,
                            accumulator.completeNormally(), null));
        } catch (RuntimeException subscribeError) {
            // 구독을 걸기도 전에 실패한 경우(예: 클라이언트가 스트림을 만들다 즉시 던짐).
            // 이미 예약·USER 저장이 끝난 뒤이므로 실패 경로를 그대로 태워 예약을 되돌린다.
            log.error("AI 응답 생성 구독 시작 실패 sessionId={}", turn.sessionId(), subscribeError);
            submitPostProcessing(turn, deliveryChannel, postProcessingSubmitted,
                    accumulator.failWithStreamError(), subscribeError);
        }
    }

    /**
     * 기한 둘을 건 생성 스트림.
     *
     * <p>무응답 기한({@code timeout})의 시작점은 구독 시점이고, 그 뒤로는 <b>청크 1건을 받을 때마다</b>
     * 다시 시작된다. 무엇을 수신으로 인정하는지: <b>본문 델타뿐 아니라 종료 사유·사용량만 실린 청크도 인정한다.</b>
     * 이 기한이 재는 것은 "답변이 늘고 있는가" 가 아니라 "공급자가 아직 응답을 보내고 있는가" 이고,
     * 메타데이터·사용량 전용 청크도 정상 응답 모양({@link AiChatStreamChunk#isMetadataOnly()})이라
     * 그것을 무응답으로 세면 정상 스트림의 마지막 구간을 끊게 된다.
     *
     * <p>전체 기한은 첫 신호와 무관하게 구독 시점부터 한 번만 잰다 — 청크가 계속 도착해도 적용하는 절대 상한이다.
     * 기한이 되면 오류 신호를 흘려 넣어 상류 구독을 취소시킨다(정상 완료로 끝내면 판정이 성공 쪽 규칙을 타게 된다).
     */
    private Flux<AiChatStreamChunk> generationStream(PreparedChatTurn turn) {
        AiChatProperties.Streaming streaming = aiChatProperties.streaming();
        Duration idleTimeout = Duration.ofSeconds(streaming.generationIdleTimeoutSeconds());
        Duration totalTimeout = Duration.ofSeconds(streaming.generationTotalTimeoutSeconds());
        return aiChatClient.generateStream(turn.streamCommand())
                .timeout(idleTimeout)
                .takeUntilOther(Mono.delay(totalTimeout)
                        .then(Mono.error(() -> new TimeoutException(
                                "AI 응답 생성이 전체 기한(" + totalTimeout.toSeconds() + "초)을 넘겼습니다."))));
    }

    /** 청크 1건 — 누적하고, 본문 조각이면 전달 큐에 넣어 본다. 큐가 받지 못해도(포화·종료) 생성은 그대로 이어진다. */
    private void receiveChunk(
            AiChatStreamChunk chunk, AiChatGenerationAccumulator accumulator, ChatDeliveryChannel deliveryChannel) {
        accumulator.accept(chunk);
        if (chunk.hasDelta()) {
            deliveryChannel.offerDelta(chunk.delta());
        }
    }

    /** 기한 초과와 그 밖의 스트림 오류를 나눠 판정한다 — 사후 확인에서 "왜 끊겼는가" 가 남게 하려는 구분이다. */
    private AiChatGenerationOutcome judgeFailedStream(
            AiChatGenerationAccumulator accumulator, Throwable generationError) {
        return (generationError instanceof TimeoutException)
                ? accumulator.failWithTimeout()
                : accumulator.failWithStreamError();
    }

    /**
     * 저장·정산 또는 실패 보상을 작업별 VT 에 넘긴다. 이 호출은 전송 계층 스레드(청크 종료 훅)에서 일어나므로
     * 여기서 DB 를 만지지 않는다.
     *
     * <p>후처리 동시성 상한이나 저장·정산 전체 타이머는 두지 않는다 — 무한정 매달리는 것을 막는 것은
     * 이미 자리 잡은 DB 쪽 대기 제한(연결 획득 기한·잠금 대기 기한)의 몫이다.
     *
     * <p>제출이 거절되면(종료 절차로 실행기가 닫힌 뒤) 저장·정산은 <b>일어나지 않은 것</b>이다.
     * 삼켜서 성공으로 기록하지 않고, 진행 목록만 정리한 뒤 미종료로 남은 요청 기록을 예약 복구에 맡긴다.
     */
    private void submitPostProcessing(
            PreparedChatTurn turn,
            ChatDeliveryChannel deliveryChannel,
            AtomicBoolean postProcessingSubmitted,
            AiChatGenerationOutcome generation,
            Throwable generationError
    ) {
        if (!postProcessingSubmitted.compareAndSet(false, true)) {
            return;
        }
        try {
            aiChatPostProcessingExecutor.execute(
                    () -> finishTurn(turn, deliveryChannel, generation, generationError));
        } catch (RejectedExecutionException postProcessingRejected) {
            log.error("채팅 턴 후처리 제출 거절 — 저장·정산을 시작하지 못했다."
                            + " 요청 기록은 미종료로 남아 예약 복구 대상이다 turnRequestId={} sessionId={}",
                    turn.turnRequestId(), turn.sessionId(), postProcessingRejected);
            deliveryChannel.completeWithFailure(errorEvent(AiChatErrorCode.SERVER_SHUTTING_DOWN, null));
            aiChatInFlightTurnRegistry.finish(turn.inFlightTurn());
        }
    }

    /**
     * 후처리 VT 의 본체 — 생성 판정에 따라 성공 확정 또는 청구 없는 종료를 수행하고, 그 결과를 전달 채널에 알린다.
     * 전달의 완료·종료를 기다리지 않는다.
     *
     * <p>어떤 이유로 끝나든 <b>마지막에 진행 목록의 자리를 정리한다</b>. 다만 자리를 지우는 것은
     * "이번 실행이 끝났다" 는 뜻이지 "업무가 성공했다" 는 뜻이 아니다 — 확정하지 못한 요청 기록은
     * 미종료로 남아 예약 복구의 대상이 된다.
     */
    private void finishTurn(
            PreparedChatTurn turn,
            ChatDeliveryChannel deliveryChannel,
            AiChatGenerationOutcome generation,
            Throwable generationError
    ) {
        try {
            if (generation.isSuccess()) {
                finishSucceededTurn(turn, deliveryChannel, generation);
            } else {
                finishFailedTurn(turn, deliveryChannel, generation, generationError);
            }
        } catch (RuntimeException postProcessingError) {
            log.error("채팅 턴 후처리 실패 — 요청 기록은 미종료로 남아 예약 복구 대상이다"
                            + " turnRequestId={} sessionId={} 생성판정={}",
                    turn.turnRequestId(), turn.sessionId(), generation.status(), postProcessingError);
            deliveryChannel.completeWithFailure(errorEvent(AiChatErrorCode.AI_STREAM_INTERRUPTED, null));
        } finally {
            aiChatInFlightTurnRegistry.finish(turn.inFlightTurn());
        }
    }

    /**
     * 정상 완료: 답변 저장·정산·예산 보정·요청 성공 확정을 한 트랜잭션으로 확정하고, 그 뒤에만 성공을 알린다.
     * 게이트 계상은 되돌리지 않는다 — 생성이 실제로 일어나 토큰이 소모됐으므로 계상이 맞다.
     */
    private void finishSucceededTurn(
            PreparedChatTurn turn, ChatDeliveryChannel deliveryChannel, AiChatGenerationOutcome generation) {
        AiChatTurnOutcomeWriter.TurnOutcomeResult outcomeResult;
        try {
            outcomeResult = aiChatTurnOutcomeWriter.finishSuccessfully(
                    turn.turnRequestId(), generation, turn.estimatedMessageInputTokens());
        } catch (RuntimeException commitError) {
            log.error("성공 확정 커밋 실패 — 현재 요청 상태를 다시 확인한다 turnRequestId={} sessionId={}",
                    turn.turnRequestId(), turn.sessionId(), commitError);
            recheckUncertainSuccessCommit(turn, deliveryChannel, commitError);
            return;
        }
        notifySuccessOutcome(turn, deliveryChannel, generation, outcomeResult);
    }

    /**
     * 커밋 응답이 불확실하게 끊긴 뒤의 재확인. 예외만 보고 환불로 직행하면 <b>이미 성공 커밋된 요청의 예약까지</b>
     * 되돌릴 수 있으므로, 잠그지 않고 현재 상태를 다시 읽어 판단한다.
     *
     * <p>아직 미종료라면 커밋이 반영되지 않은 것이라 청구 없이 끝내고 예약을 되돌린다. 이미 종료돼 있다면
     * 커밋이 실제로는 반영된 것이므로 <b>아무것도 되돌리지 않는다</b>. 다만 이 경로에는 저장된 답변의
     * 토큰 수·저장 시각이 손에 없어 성공 이벤트를 만들 수 없으므로, 연결에는 오류로 끝을 알린다 —
     * 답변의 정본은 DB 에 있고, 클라이언트는 이력 조회로 확인한다.
     */
    private void recheckUncertainSuccessCommit(
            PreparedChatTurn turn, ChatDeliveryChannel deliveryChannel, RuntimeException commitError) {
        AiChatTurnOutcomeWriter.TurnOutcomeResult currentOutcome =
                aiChatTurnOutcomeWriter.currentOutcome(turn.turnRequestId());
        if (currentOutcome instanceof AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning) {
            // 생성은 성공했지만 답변이 저장되지 않았다 — 청구하지 않는다.
            // 게이트 계상은 되돌리지 않는다(토큰은 실제로 소모됐다).
            aiChatTurnOutcomeWriter.finishWithoutCharge(
                    turn.turnRequestId(), AiChatTurnRequest.Status.FAILED, failureCodeOf(commitError));
            deliveryChannel.completeWithFailure(errorEvent(AiChatErrorCode.AI_STREAM_INTERRUPTED, null));
            return;
        }
        log.warn("성공 확정 커밋이 실제로는 반영돼 있었다 — 예약을 되돌리지 않는다 turnRequestId={} 현재상태={}",
                turn.turnRequestId(), currentOutcome);
        deliveryChannel.completeWithFailure(errorEvent(AiChatErrorCode.AI_STREAM_INTERRUPTED, null));
    }

    /**
     * 확정 결과를 연결에 알린다. <b>이번 호출이 확정한 경우에만</b> 성공을 알린다 —
     * 이미 다른 실행(만료 복구 등)이 끝낸 요청이면 그 요청은 이 답변으로 끝난 것이 아니다.
     */
    private void notifySuccessOutcome(
            PreparedChatTurn turn,
            ChatDeliveryChannel deliveryChannel,
            AiChatGenerationOutcome generation,
            AiChatTurnOutcomeWriter.TurnOutcomeResult outcomeResult
    ) {
        if (outcomeResult instanceof AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded succeeded) {
            AiChatTurnOutcomeWriter.SavedAssistantMessage saved = succeeded.assistantMessage();
            deliveryChannel.completeWithSuccess(
                    generation.content(),
                    new MessageStreamEvent.TokenCount(
                            saved.inputTokens(), saved.outputTokens(), saved.totalTokens()),
                    saved.createdAt());
            return;
        }
        log.warn("이미 종료된 요청이라 성공을 새로 알리지 않는다 turnRequestId={} 확정결과={}",
                turn.turnRequestId(), outcomeResult);
        deliveryChannel.completeWithFailure(errorEvent(AiChatErrorCode.AI_STREAM_INTERRUPTED, null));
    }

    /**
     * 생성 실패: 청구 없이 요청을 끝내고(상태 전이 + 예약 반환) 게이트 계상을 보상 차감한다.
     * <b>받은 데까지의 부분 본문은 저장하지 않는다</b>(설계 정본 §6.3).
     *
     * <p>게이트 보상의 기준은 <b>생성 판정이 성공이 아닌 것</b> 하나다. 실패의 대부분(연결 실패·4xx·429·기한 초과)은
     * OpenAI 가 토큰을 소모하지 않아 보상이 실제와 맞는다. 반대로 스트림은 끝났는데 판정에서 걸린 경우
     * (종료 사유 누락·비정상 종료 사유·사용량 없음)는 이미 과금된 뒤일 수 있어 실제보다 많이 되돌리는 셈이 된다 —
     * 기존 실패 보상에도 있던 성격의 어림이고, 보상은 확보 당시의 분 키를 되돌리므로 분을 넘겨 도착한 보상이
     * 현재 분 예산을 부풀리지는 않는다.
     */
    private void finishFailedTurn(
            PreparedChatTurn turn,
            ChatDeliveryChannel deliveryChannel,
            AiChatGenerationOutcome generation,
            Throwable generationError
    ) {
        log.warn("AI 응답 생성 실패 turnRequestId={} sessionId={} 판정={} 종료사유={} error={}",
                turn.turnRequestId(), turn.sessionId(), generation.status(), generation.finishReason(),
                generationError == null ? "없음" : generationError.toString());

        aiChatTurnOutcomeWriter.finishWithoutCharge(
                turn.turnRequestId(), AiChatTurnRequest.Status.FAILED, generation.status().name());
        // 게이트 보상은 DB 트랜잭션 밖에서 — Redis 왕복을 커밋 시간에 매달지 않는다.
        releaseRateLimitPermitQuietly(turn.rateLimitPermit(), turn.sessionId());
        deliveryChannel.completeWithFailure(buildErrorEvent(generationError));
    }

    /** 예산 예약 결과 + 그 예약에 쓰인 메시지 입력 추정 토큰. */
    private record BudgetReservation(
            UserTokenBudgetWriter.ReserveResult.Granted granted,
            int estimatedMessageInputTokens
    ) {
    }

    /** 이력 조회 단계의 결과 — 대화 이력과 책 정보(없으면 null). */
    private record TurnContext(
            AiChatMessagePersistService.MessageLoadResult loaded,
            AiChatStreamCommand.BookContext bookContext
    ) {
    }

    /**
     * 예산 예약과 요청 기록의 예약 정보를 한 트랜잭션으로 커밋한다 (AiChatTurnRequestWriter#reserveWithRecord) —
     * 예약량·예산 기간이 요청 행에 함께 남아야 비정상 종료 뒤에도 무엇을 얼마나 되돌릴지 알 수 있다.
     */
    private BudgetReservation reserveTokenBudget(Long turnRequestId, Long userId, String normalizedContent) {
        int estimatedMessageInputTokens = tokenCounter.count(normalizedContent);
        int reservedTokens = estimatedMessageInputTokens + aiChatProperties.tokenBudget().estimatedOutputTokens();
        // DB 원장이 정본이라 우회(fail-open) 경로가 없다 — reserve 의 DB 예외는 그대로 전파한다(500).
        UserTokenBudgetWriter.ReserveResult reserveResult =
                aiChatTurnRequestWriter.reserveWithRecord(turnRequestId, userId, reservedTokens);
        if (reserveResult instanceof UserTokenBudgetWriter.ReserveResult.Denied denied) {
            throw tokenBudgetExceeded(denied.retryAfter());
        }
        return new BudgetReservation(
                (UserTokenBudgetWriter.ReserveResult.Granted) reserveResult, estimatedMessageInputTokens);
    }

    private TurnContext loadTurnContext(Long sessionId, Long userId) {
        AiChatMessagePersistService.MessageLoadResult loaded =
                aiChatMessagePersistService.loadHistory(sessionId, userId);
        return new TurnContext(loaded, resolveBookContext(loaded.userBookId()));
    }

    private void applyModerationDecision(
            InputModerationResult moderation, Long sessionId, Long userId, String normalizedContent) {
        switch (moderation.status()) {
            case BLOCKED -> {
                aiChatMessagePersistService.recordRejectedUserMessage(sessionId, normalizedContent);
                log.warn("[Guardrail] 입력 차단 sessionId={} userId={} categories={}",
                        sessionId, userId, moderation.flaggedCategories());
                throw new BadRequestException(AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT);
            }
            case UNAVAILABLE -> throw new ServiceUnavailableException(AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE);
            case PASSED -> { }
        }
    }

    private AiChatStreamCommand buildStreamCommand(
            Long sessionId, String normalizedContent, TurnContext turnContext) {
        AiChatMessagePersistService.MessageLoadResult loaded = turnContext.loaded();
        List<HistoryMessage> withCurrent = new ArrayList<>(loaded.notSummarizedChatRaws().size() + 1);
        withCurrent.addAll(loaded.notSummarizedChatRaws());
        withCurrent.add(new HistoryMessage(HistoryMessage.Role.USER, normalizedContent));
        return new AiChatStreamCommand(
                sessionId, withCurrent, turnContext.bookContext(), loaded.contextSummary());
    }

    private void recordUserMessageOrCompensate(
            Long sessionId, Long userId, String normalizedContent, AiChatClient.RateLimitPermit rateLimitPermit) {
        try {
            aiChatMessagePersistService.recordUserMessage(sessionId, userId, normalizedContent);
        } catch (RuntimeException userMessagePersistError) {
            // 게이트 확보 이후·생성 이전의 실패 — 생성이 일어나지 않아 OpenAI 토큰 소모가 없으므로
            // 분당 계상을 보상 차감한다. 예약 환불은 prepare() 의 공통 환불 경로가 담당한다.
            releaseRateLimitPermitQuietly(rateLimitPermit, sessionId);
            throw userMessagePersistError;
        }
    }

    /**
     * 환불 실패는 삼킨다 — 선행 처리 거절 경로에서 던지면 원래의 4xx 가 500 으로 둔갑하고,
     * 생성·저장 실패 경로에서 던지면 error 이벤트 전달이 막힌다.
     */
    private void refundQuietly(Long userId, UserTokenBudgetWriter.ReserveResult.Granted reservation, Long sessionId) {
        try {
            userTokenBudgetWriter.refund(userId, reservation.periodKey(), reservation.reservedTokens());
        } catch (RuntimeException refundError) {
            log.error("토큰 예산 환불 실패 userId={} sessionId={}", userId, sessionId, refundError);
        }
    }

    /**
     * 게이트 보상 차감 실패는 삼킨다 — 분 창 만료(최대 60초)가 안전망이라 실패가
     * 응답 경로(선행 처리의 원래 예외 전파·생성 단계의 error 이벤트 전달)를 막을 이유가 없다.
     */
    private void releaseRateLimitPermitQuietly(AiChatClient.RateLimitPermit rateLimitPermit, Long sessionId) {
        try {
            aiChatClient.releaseRateLimitPermit(rateLimitPermit);
        } catch (RuntimeException releaseError) {
            log.error("전역 게이트 보상 차감 실패 sessionId={}", sessionId, releaseError);
        }
    }

    private TooManyRequestsException tokenBudgetExceeded(Duration retryAfter) {
        AiChatProperties.TokenBudget budget = aiChatProperties.tokenBudget();
        RateLimitInfo info = new RateLimitInfo(
                retryAfter, null, (long) budget.dailyTokens(), null, 0L, null, retryAfter);
        return new TooManyRequestsException(AiChatErrorCode.USER_TOKEN_BUDGET_EXCEEDED, info);
    }

    /**
     * 실패 원인을 클라이언트가 볼 error 이벤트로 옮긴다. 원인 예외가 없는 실패(스트림은 끝났는데 판정에서
     * 걸린 경우)는 일반 중단 코드로 알린다 — 공급자가 준 종료 사유는 사용자 화면에서 쓸 값이 아니다.
     */
    private MessageStreamEvent.Error buildErrorEvent(Throwable error) {
        if (error == null) {
            return errorEvent(AiChatErrorCode.AI_STREAM_INTERRUPTED, null);
        }
        RateLimitInfo rateLimitInfo = (error instanceof TooManyRequestsException tooMany)
                ? tooMany.getRateLimitInfo()
                : null;
        return errorEvent(toErrorCode(error), rateLimitInfo);
    }

    private MessageStreamEvent.Error errorEvent(AiChatErrorCode code, RateLimitInfo rateLimitInfo) {
        return new MessageStreamEvent.Error(code.name(), code.getMessage(), rateLimitInfo);
    }

    private AiChatErrorCode toErrorCode(Throwable error) {
        // OpenAiResponseErrorHandler 가 이미 도메인 예외(TooManyRequestsException 포함) 로
        // 분류해 던지므로, BusinessException + AiChatErrorCode 케이스 하나로 처리된다.
        if (error instanceof BusinessException businessException
                && businessException.getErrorCode() instanceof AiChatErrorCode aiChatErrorCode) {
            return aiChatErrorCode;
        }
        if (error instanceof TransientAiException) {
            return AiChatErrorCode.AI_PROVIDER_TRANSIENT;
        }
        if (error instanceof NonTransientAiException) {
            return AiChatErrorCode.AI_PROVIDER_ERROR;
        }
        return AiChatErrorCode.AI_STREAM_INTERRUPTED;
    }

    private String validateAndStripContent(String raw) {
        if (raw == null) {
            throw new BadRequestException(AiChatErrorCode.MESSAGE_CONTENT_BLANK);
        }
        // NBSP(U+00A0) 등 유니코드 공백을 ASCII 공백으로 정규화한 뒤 strip. @NotBlank/strip() 이 놓치는 공백-only 입력을 빈 본문으로 거절.
        String normalized = WHITESPACE_RUN.matcher(raw).replaceAll(" ").strip();
        if (normalized.isEmpty()) {
            throw new BadRequestException(AiChatErrorCode.MESSAGE_CONTENT_BLANK);
        }
        if (normalized.length() > aiChatProperties.message().maxContentLength()) {
            throw new BadRequestException(AiChatErrorCode.MESSAGE_CONTENT_TOO_LONG);
        }
        return normalized;
    }

    private AiChatStreamCommand.BookContext resolveBookContext(Long userBookId) {
        return userBookRepository.findById(userBookId)
                .flatMap(userBook -> bookRepository.findById(userBook.getBookId()))
                .map(book -> new AiChatStreamCommand.BookContext(
                        book.getTitle(),
                        book.getAuthors(),
                        book.getPublisher()
                ))
                .orElse(null);
    }

    /**
     * 사용자별 폭주 차단 — 10초 안에 5건 이상은 정상 사용이 아니라고 보고 거절한다.
     * 비용 방어의 본체는 토큰 예산(userTokenBudgetWriter)이고, 이 가드는 초 단위 폭주만 막는다.
     * Redis ZSET + Lua 로 검사와 기록을 원자로 수행한다 — 구 DB 카운트 방식은 검사와
     * USER 저장 사이 간격 때문에 동시 요청이 전부 통과했다.
     * 슬롯은 검사 시점에 즉시 소모되고, 뒤 단계(모더레이션 차단·예산 거절 등)에서
     * 거절돼도 반환하지 않는다 — 폭주 차단이라는 목적상 시도 자체를 세는 것이 맞다.
     * retryAfter 는 카운트 기간을 그대로 돌려 보낸다 (보수적 추정).
     */
    private void verifyUserMessageRateLimit(Long userId) {
        AiChatProperties.RateLimit limit = aiChatProperties.rateLimit();
        switch (userMessageRateLimiter.tryConsume(userId)) {
            case UserMessageRateLimiter.Result.Denied() ->
                    throw rateLimitExceeded(limit.countPeriodSeconds(), limit.maxMessageCount());
            case UserMessageRateLimiter.Result.Allowed() -> { }
            case UserMessageRateLimiter.Result.Bypassed() -> { }
        }
    }

    private TooManyRequestsException rateLimitExceeded(int periodSeconds, int maxCount) {
        RateLimitInfo info = new RateLimitInfo(
                Duration.ofSeconds(periodSeconds),
                (long) maxCount,
                null,
                0L,
                null,
                null,
                null
        );
        return new TooManyRequestsException(AiChatErrorCode.USER_RATE_LIMIT_EXCEEDED, info);
    }
}
