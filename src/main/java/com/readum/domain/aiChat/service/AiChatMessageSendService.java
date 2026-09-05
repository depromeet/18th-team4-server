package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatCompletion;
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
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatMessage;
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
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
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
    private final TokenCounter tokenCounter;

    /**
     * 선행 처리 결과 — 생성 단계(generateAndPersistStream)에 필요한 모든 문맥. 예약(reservation)은 항상 존재한다.
     * rateLimitPermit 은 전역 게이트 확보 결과로 항상 존재한다 (게이트가 검사 없이 통과시켰다면 계상 없는 permit).
     */
    public record PreparedChatTurn(
            Long userId,
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
     * 선행 처리(요청의 가상 스레드에서 동기 순차): 본문 검증 → 사용자 폭주 가드 → 예산 예약 → 이력 조회 →
     * 입력 모더레이션 → 전역 게이트 → USER 저장.
     * 각 단계는 앞 단계의 결과를 보고 다음을 정하는 순차 업무이고, 요청 스레드가 가상 스레드라
     * 여기서 기다려도 다른 요청의 처리를 막지 않는다 — 그래서 리액티브 체인 밖에 둔다.
     * 여기서 던진 예외는 SSE 시작 전이라 GlobalExceptionHandler 가 4xx/5xx JSON 으로 변환한다.
     * 예산 예약을 moderation 앞에 두는 이유: 예산이 소진된 사용자가 공짜 moderation 호출
     * (제공자 RPM 자원)을 소모하지 못하게 한다 — 토큰 추정(tokenCounter)은 로컬 계산이라
     * 예약을 앞으로 당겨도 외부 비용이 없다.
     */
    public PreparedChatTurn prepare(SendMessageCommand command) {
        String normalizedContent = validateAndStripContent(command.content());
        Long sessionId = command.sessionId();
        Long userId = command.userId();

        verifyUserMessageRateLimit(userId);

        BudgetReservation reservation = reserveTokenBudget(userId, normalizedContent);

        // 예약 이후의 모든 거절·실패(이력 조회 실패, moderation 차단/불능, 게이트 거절, USER 저장 실패)는
        // 생성이 시작되지 않은 경우라 예약을 전액 환불하고 원래 예외를 그대로 던진다.
        try {
            return prepareAfterReservation(sessionId, userId, normalizedContent, reservation);
        } catch (RuntimeException prepareRejection) {
            refundQuietly(userId, reservation.granted(), sessionId);
            throw prepareRejection;
        }
    }

    private PreparedChatTurn prepareAfterReservation(
            Long sessionId,
            Long userId,
            String normalizedContent,
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
                streamCommand,
                reservation.granted(),
                reservation.estimatedMessageInputTokens(),
                rateLimitPermit);
    }

    /**
     * 생성 단계: 스트리밍 청크 소비 → SSE 로 내보낼 Token 이벤트 → 완료 시 저장·정산 후 Done 이벤트.
     * 청크 처리(누적)와 그 뒤의 저장·정산은 {@code publishOn(boundedElastic)} 으로 공유 풀에 오프로딩한다 —
     * 전송 계층 스레드에서 블로킹 작업을 하지 않기 위함이고, 구독자(컨트롤러)의 SSE 전송도 같은 신호 위에서 일어난다.
     * 예외를 밖으로 던지지 않고 error 이벤트로 변환한다 (기존 generateAndPersist 와 동일한 계약).
     * 클라이언트가 도중에 이탈해도 구독은 서버가 소유하므로 끝까지 소비·저장·정산한다(스펙 §5-1).
     */
    public Flux<MessageStreamEvent> generateAndPersistStream(PreparedChatTurn turn) {
        // 리액티브 신호는 직렬로 전달되므로(같은 시퀀스 내 happens-before) 누적 상태에 별도 동기화가 필요 없다.
        StringBuilder accumulatedContent = new StringBuilder();
        AtomicReference<AiChatStreamChunk> measuredUsage = new AtomicReference<>();

        Flux<MessageStreamEvent> tokenEvents = aiChatClient.generateStream(turn.streamCommand())
                .publishOn(Schedulers.boundedElastic())
                .<MessageStreamEvent>handle((chunk, sink) -> {
                    // 사용량은 청크별로 더하지 않는다 — 마지막으로 받은 유효 사용량이 그 턴의 실측이다.
                    if (chunk.hasValidUsage()) {
                        measuredUsage.set(chunk);
                    }
                    if (chunk.hasDelta()) {
                        accumulatedContent.append(chunk.delta());
                        sink.next(new MessageStreamEvent.Token(chunk.delta()));
                    }
                });

        return tokenEvents
                .concatWith(Mono.defer(() -> offload(() ->
                        persistAndSettle(turn, accumulatedContent.toString(), measuredUsage.get()))))
                .onErrorResume(generateError -> offload(() ->
                        generationFailureEvent(turn, accumulatedContent.toString(), generateError)));
    }

    /** 스트림 정상 완주: ASSISTANT 저장 → 정산 → Done 이벤트. 저장 실패는 error 이벤트로 바꿔 돌려준다(밖으로 던지지 않는다). */
    private MessageStreamEvent persistAndSettle(
            PreparedChatTurn turn, String accumulatedContent, AiChatStreamChunk measuredUsage) {
        AiChatCompletion completion = new AiChatCompletion(
                accumulatedContent,
                measuredUsage == null ? null : measuredUsage.inputTokens(),
                measuredUsage == null ? null : measuredUsage.outputTokens(),
                measuredUsage == null ? null : measuredUsage.totalTokens(),
                null);
        try {
            AiChatMessage saved = aiChatMessagePersistService.saveAssistantSuccess(
                    turn.sessionId(), accumulatedContent, completion);
            settleOnSuccess(turn, completion, saved);
            return new MessageStreamEvent.Done(
                    new MessageStreamEvent.TokenCount(
                            saved.getInputTokens(), saved.getOutputTokens(), saved.getTotalTokens()),
                    saved.getCreatedAt());
        } catch (RuntimeException persistError) {
            // 생성은 성공(과금 완료)했는데 저장이 실패한 경우 — 본문·실측 토큰을 FAILED 행으로 보존한다.
            // 게이트 계상은 보상하지 않는다 — 토큰이 실제로 소모돼 계상이 맞다.
            log.error("AI 응답 저장 실패 sessionId={}", turn.sessionId(), persistError);
            persistFailedQuietly(turn.sessionId(), accumulatedContent, completion);
            refundReservation(turn);
            return buildErrorEvent(persistError);
        }
    }

    /** 생성 스트림 실패: FAILED 저장(받은 데까지의 본문 보존) + 예약 환불 + 게이트 보상 + error 이벤트. */
    private MessageStreamEvent generationFailureEvent(
            PreparedChatTurn turn, String accumulatedContent, Throwable generateError) {
        log.error("AI 응답 생성 실패 sessionId={} error={}", turn.sessionId(), generateError.toString());
        persistFailedQuietly(turn.sessionId(), accumulatedContent, null);
        refundReservation(turn);
        // 생성 실패면 게이트 분당 계상을 보상 차감한다. 실패의 대부분(연결 실패·4xx·429)은
        // OpenAI 가 토큰을 소모하지 않아 보상이 실제와 맞지만, HTTP 200 을 받은 뒤 응답을 읽거나
        // 변환하다 실패한 경우는 이미 과금된 뒤라 실제보다 많이 되돌리는 셈이 된다. 그래도 현재 분
        // 예산이 부풀지는 않는다 — 보상은 확보 당시의 분 키를 되돌리므로, 분을 넘겨 도착한 실패는
        // 이미 지나간 창을 건드린다.
        releaseRateLimitPermitQuietly(turn.rateLimitPermit(), turn.sessionId());
        return buildErrorEvent(generateError);
    }

    /**
     * 블로킹 작업 1건을 전역 공유 boundedElastic 으로 오프로딩한다.
     * 결과가 null 이면 Mono 가 빈 채로 끝나 뒤 단계가 통째로 건너뛰어지므로, 조용히 사라지지 않도록 에러로 바꾼다.
     */
    private static <T> Mono<T> offload(Callable<T> blockingWork) {
        return Mono.fromCallable(blockingWork)
                .switchIfEmpty(Mono.error(() ->
                        new IllegalStateException("오프로딩한 단계가 결과 없이(null) 끝났습니다.")))
                .subscribeOn(Schedulers.boundedElastic());
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

    private BudgetReservation reserveTokenBudget(Long userId, String normalizedContent) {
        int estimatedMessageInputTokens = tokenCounter.count(normalizedContent);
        int reservedTokens = estimatedMessageInputTokens + aiChatProperties.tokenBudget().estimatedOutputTokens();
        // DB 원장이 정본이라 우회(fail-open) 경로가 없다 — reserve 의 DB 예외는 그대로 전파한다(500).
        UserTokenBudgetWriter.ReserveResult reserveResult = userTokenBudgetWriter.reserve(userId, reservedTokens);
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
     * 성공: 사용자 예산은 메시지 입력 추정 + 실측 출력만 계상 (기존 정책 동일).
     * 멱등 키는 저장된 ASSISTANT 메시지 id — 같은 메시지의 이중 정산은 정산 기록의
     * UNIQUE 위반(트랜잭션 전체 롤백)으로 차단되므로 "이미 정산됨" 으로 로그 후 무시한다.
     * 그 밖의 정산 실패도 삼킨다 — 응답은 이미 저장·전달 대상이므로, 여기서 던지면 정상 완료된 턴이
     * 실패 경로(FAILED 중복 저장 + 이중 정산)로 뒤집힌다. 옛 코드도 보정을 fire-and-forget 으로 돌렸다.
     */
    private void settleOnSuccess(PreparedChatTurn turn, AiChatCompletion completion, AiChatMessage savedAssistantMessage) {
        try {
            int outputTokens = completion.outputTokens() != null
                    ? completion.outputTokens()
                    : tokenCounter.count(completion.content());
            userTokenBudgetWriter.settle(
                    turn.userId(),
                    turn.reservation().periodKey(),
                    savedAssistantMessage.getId(),
                    turn.reservation().reservedTokens(),
                    turn.estimatedMessageInputTokens() + outputTokens);
        } catch (DataIntegrityViolationException alreadySettled) {
            log.warn("토큰 예산 정산 생략 — 이미 정산된 메시지 userId={} messageId={}",
                    turn.userId(), savedAssistantMessage.getId());
        } catch (RuntimeException settleError) {
            log.error("토큰 예산 정산 실패(성공 응답은 유지) userId={} sessionId={}", turn.userId(), turn.sessionId(), settleError);
        }
    }

    /**
     * 생성·저장 실패: 사용자 과실이 아니므로 예약 전액 환불 (기존 정책 동일).
     * 선행 처리의 거절(게이트 거절·moderation 차단 등)은 여기가 아니라
     * prepare() 의 공통 환불 경로가 담당한다.
     */
    private void refundReservation(PreparedChatTurn turn) {
        refundQuietly(turn.userId(), turn.reservation(), turn.sessionId());
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

    /** 실패 저장이 또 실패해도 error 이벤트 전달을 막지 않는다. */
    private void persistFailedQuietly(Long sessionId, String content, AiChatCompletion meta) {
        try {
            aiChatMessagePersistService.saveAssistantFailed(sessionId, content, meta);
        } catch (RuntimeException persistError) {
            log.error("AI FAILED 메시지 영속화 실패 sessionId={}", sessionId, persistError);
        }
    }

    private TooManyRequestsException tokenBudgetExceeded(Duration retryAfter) {
        AiChatProperties.TokenBudget budget = aiChatProperties.tokenBudget();
        RateLimitInfo info = new RateLimitInfo(
                retryAfter, null, (long) budget.dailyTokens(), null, 0L, null, retryAfter);
        return new TooManyRequestsException(AiChatErrorCode.USER_TOKEN_BUDGET_EXCEEDED, info);
    }

    private MessageStreamEvent.Error buildErrorEvent(Throwable error) {
        AiChatErrorCode code = toErrorCode(error);
        RateLimitInfo rateLimitInfo = (error instanceof TooManyRequestsException tooMany)
                ? tooMany.getRateLimitInfo()
                : null;
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
