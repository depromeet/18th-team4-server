package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatCompletion;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
     * 사전 단계 결과 — 생성 단계(generateAndPersist)에 필요한 모든 문맥. 예약(reservation)은 항상 존재한다.
     * rateLimitPermit 은 전역 게이트 확보 결과로 항상 존재한다 (게이트 fail-open 통과면 계상 없는 permit).
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
     * 사전 단계(요청 스레드, 동기): rate limit → 예산 예약 → 이력 조회 → 입력 모더레이션 → 전역 게이트 → USER 저장.
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

        int estimatedMessageInputTokens = tokenCounter.count(normalizedContent);
        int reservedTokens = estimatedMessageInputTokens + aiChatProperties.tokenBudget().estimatedOutputTokens();
        // DB 원장이 정본이라 우회(fail-open) 경로가 없다 — reserve 의 DB 예외는 그대로 전파한다(500).
        UserTokenBudgetWriter.ReserveResult reserveResult = userTokenBudgetWriter.reserve(userId, reservedTokens);
        if (reserveResult instanceof UserTokenBudgetWriter.ReserveResult.Denied denied) {
            throw tokenBudgetExceeded(denied.retryAfter());
        }
        UserTokenBudgetWriter.ReserveResult.Granted reservation =
                (UserTokenBudgetWriter.ReserveResult.Granted) reserveResult;

        // 예약 이후의 모든 거절·실패(이력 조회 실패, moderation 차단/불능, 게이트 거절, USER 저장 실패)는
        // 생성이 시작되지 않은 경우라 예약을 전액 환불하고 원래 예외를 그대로 던진다.
        try {
            return prepareAfterReservation(
                    sessionId, userId, normalizedContent, reservation, estimatedMessageInputTokens);
        } catch (RuntimeException prepareRejection) {
            refundQuietly(userId, reservation, sessionId);
            throw prepareRejection;
        }
    }

    private PreparedChatTurn prepareAfterReservation(
            Long sessionId,
            Long userId,
            String normalizedContent,
            UserTokenBudgetWriter.ReserveResult.Granted reservation,
            int estimatedMessageInputTokens
    ) {
        AiChatMessagePersistService.MessageLoadResult loaded =
                aiChatMessagePersistService.loadHistory(sessionId, userId);
        AiChatStreamCommand.BookContext bookContext = resolveBookContext(loaded.userBookId());

        InputModerationResult moderation = inputModerationClient.check(normalizedContent, bookContext);
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

        List<HistoryMessage> withCurrent = new ArrayList<>(loaded.notSummarizedChatRaws().size() + 1);
        withCurrent.addAll(loaded.notSummarizedChatRaws());
        withCurrent.add(new HistoryMessage(HistoryMessage.Role.USER, normalizedContent));

        AiChatStreamCommand streamCommand = new AiChatStreamCommand(
                sessionId, withCurrent, bookContext, loaded.contextSummary());

        // 전역 게이트: 옛 코드처럼 SSE 시작 전에 확보한다. USER 저장보다 먼저 확인해
        // 거절(429) 시 응답 없는 USER 메시지가 대화 이력에 남지 않게 한다.
        // 거절 시 예약 환불은 prepare() 의 공통 환불 경로가 담당하고, 게이트 분당 계상은
        // tryAcquire 가 거절하면서 스스로 되돌렸으므로 여기서 또 보상하면 이중 차감이다.
        AiChatClient.RateLimitPermit rateLimitPermit = aiChatClient.acquireRateLimitPermit(streamCommand);

        try {
            aiChatMessagePersistService.recordUserMessage(sessionId, userId, normalizedContent);
        } catch (RuntimeException userMessagePersistError) {
            // 게이트 확보 이후·생성 이전의 실패 — 생성이 일어나지 않아 OpenAI 토큰 소모가 없으므로
            // 분당 계상을 보상 차감한다. 예약 환불은 prepare() 의 공통 환불 경로가 담당한다.
            releaseRateLimitPermitQuietly(rateLimitPermit, sessionId);
            throw userMessagePersistError;
        }

        return new PreparedChatTurn(userId, streamCommand, reservation, estimatedMessageInputTokens, rateLimitPermit);
    }

    /**
     * 생성 단계(VT executor, 동기 순차): OpenAI 호출(출력 검증은 advisor 가 동기 수행) → 저장 → 예산 보정.
     * 예외를 밖으로 던지지 않고 error 이벤트로 변환한다 — SSE 는 이미 200 으로 시작된 상태라
     * 실패는 스트림 내 error 이벤트가 유일한 전달 수단이다 (기존 onErrorResume 과 동일한 계약).
     * 클라이언트가 도중에 이탈해도 끝까지 생성·저장한다(스펙 §5-1 의도된 동작 변화).
     */
    public List<MessageStreamEvent> generateAndPersist(PreparedChatTurn turn) {
        AiChatCompletion completion;
        try {
            completion = aiChatClient.generate(turn.streamCommand());
        } catch (RuntimeException generateError) {
            log.error("AI 응답 생성 실패 sessionId={} error={}", turn.sessionId(), generateError.toString());
            persistFailedQuietly(turn.sessionId(), "", null);
            refundReservation(turn);
            // 생성 실패는 OpenAI 가 토큰을 소모하지 않았으므로 게이트 분당 계상도 보상 차감한다.
            // 반대로 생성 성공 후 저장 실패는 보상하지 않는다 — 토큰이 실제로 소모돼 계상이 맞다.
            releaseRateLimitPermitQuietly(turn.rateLimitPermit(), turn.sessionId());
            return List.of(buildErrorEvent(generateError));
        }

        try {
            AiChatMessage saved = aiChatMessagePersistService.saveAssistantSuccess(
                    turn.sessionId(), completion.content(), completion);
            settleOnSuccess(turn, completion, saved);
            return List.of(
                    new MessageStreamEvent.Token(completion.content()),
                    new MessageStreamEvent.Done(
                            new MessageStreamEvent.TokenCount(
                                    saved.getInputTokens(), saved.getOutputTokens(), saved.getTotalTokens()),
                            saved.getCreatedAt()));
        } catch (RuntimeException persistError) {
            // 생성은 성공(과금 완료)했는데 저장이 실패한 경우 — 본문·실측 토큰을 FAILED 행으로 보존한다.
            log.error("AI 응답 저장 실패 sessionId={}", turn.sessionId(), persistError);
            persistFailedQuietly(turn.sessionId(), completion.content(), completion);
            refundReservation(turn);
            return List.of(buildErrorEvent(persistError));
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
     * 사전 단계의 거절(게이트 거절·moderation 차단 등)은 여기가 아니라
     * prepare() 의 공통 환불 경로가 담당한다.
     */
    private void refundReservation(PreparedChatTurn turn) {
        refundQuietly(turn.userId(), turn.reservation(), turn.sessionId());
    }

    /**
     * 환불 실패는 삼킨다 — 사전 단계 거절 경로에서 던지면 원래의 4xx 가 500 으로 둔갑하고,
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
     * 응답 경로(사전 단계의 원래 예외 전파·생성 단계의 error 이벤트 전달)를 막을 이유가 없다.
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
