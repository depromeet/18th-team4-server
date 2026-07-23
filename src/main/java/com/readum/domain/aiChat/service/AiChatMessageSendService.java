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
import com.readum.domain.aiChat.out.ChatTokenBudget;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private final AiChatMessageRepository aiChatMessageRepository;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;
    private final InputModerationClient inputModerationClient;
    private final ChatTokenBudget chatTokenBudget;
    private final TokenCounter tokenCounter;

    /** 사전 단계 결과 — 생성 단계(generateAndPersist)에 필요한 모든 문맥. */
    public record PreparedChatTurn(
            Long sessionId,
            Long userId,
            String normalizedContent,
            AiChatStreamCommand.BookContext bookContext,
            String contextSummary,
            List<HistoryMessage> historyWithCurrent,
            ChatTokenBudget.Result.Granted reservation,
            int estimatedMessageInputTokens
    ) {}

    /** Task 4 에서 컨트롤러가 SseEmitter 로 전환되면 제거되는 임시 어댑터. */
    @Deprecated
    public Flux<MessageStreamEvent> execute(SendMessageCommand command) {
        PreparedChatTurn prepared = prepare(command);
        return Flux.defer(() -> Flux.fromIterable(generateAndPersist(prepared))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 사전 단계(요청 스레드, 동기): rate limit → 이력 조회 → 입력 모더레이션 → 예산 예약 → USER 저장 → 전역 게이트.
     * 여기서 던진 예외는 SSE 시작 전이라 GlobalExceptionHandler 가 4xx/5xx JSON 으로 변환한다.
     */
    public PreparedChatTurn prepare(SendMessageCommand command) {
        String normalizedContent = validateAndStripContent(command.content());
        Long sessionId = command.sessionId();
        Long userId = command.userId();

        verifyUserMessageRateLimit(userId);

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

        int estimatedMessageInputTokens = tokenCounter.count(normalizedContent);
        int reservedTokens = estimatedMessageInputTokens + aiChatProperties.tokenBudget().estimatedOutputTokens();
        ChatTokenBudget.Result reservationResult = chatTokenBudget.reserve(userId, reservedTokens);
        if (reservationResult instanceof ChatTokenBudget.Result.Denied denied) {
            throw tokenBudgetExceeded(denied.retryAfter());
        }
        ChatTokenBudget.Result.Granted reservation =
                (reservationResult instanceof ChatTokenBudget.Result.Granted granted) ? granted : null;

        aiChatMessagePersistService.recordUserMessage(sessionId, userId, normalizedContent);

        List<HistoryMessage> withCurrent = new ArrayList<>(loaded.notSummarizedChatRaws().size() + 1);
        withCurrent.addAll(loaded.notSummarizedChatRaws());
        withCurrent.add(new HistoryMessage(HistoryMessage.Role.USER, normalizedContent));

        PreparedChatTurn prepared = new PreparedChatTurn(sessionId, userId, normalizedContent, bookContext,
                loaded.contextSummary(), withCurrent, reservation, estimatedMessageInputTokens);

        // 전역 게이트: 옛 코드처럼 SSE 시작 전에 확보한다. 거절이 예산 예약 뒤에 나므로
        // 예약을 전액 환불하고 던진다 — 옛 코드의 예약 누수(스펙 §5-5)를 고치는 의도된 개선.
        try {
            aiChatClient.acquireRateLimitPermit(new AiChatStreamCommand(
                    sessionId, withCurrent, bookContext, loaded.contextSummary()));
        } catch (RuntimeException gateRejection) {
            refundReservation(prepared);
            throw gateRejection;
        }

        return prepared;
    }

    /**
     * 생성 단계(VT executor, 동기 순차): OpenAI 호출(출력 검증은 advisor 가 동기 수행) → 저장 → 예산 보정.
     * 예외를 밖으로 던지지 않고 error 이벤트로 변환한다 — SSE 는 이미 200 으로 시작된 상태라
     * 실패는 스트림 내 error 이벤트가 유일한 전달 수단이다 (기존 onErrorResume 과 동일한 계약).
     * 클라이언트가 도중에 이탈해도 끝까지 생성·저장한다(스펙 §5-1 의도된 동작 변화).
     */
    public List<MessageStreamEvent> generateAndPersist(PreparedChatTurn turn) {
        try {
            AiChatCompletion completion = aiChatClient.generate(new AiChatStreamCommand(
                    turn.sessionId(), turn.historyWithCurrent(), turn.bookContext(), turn.contextSummary()));

            AiChatMessage saved = aiChatMessagePersistService.saveAssistantSuccess(
                    turn.sessionId(), completion.content(), completion);

            settleOnSuccess(turn, completion);

            return List.of(
                    new MessageStreamEvent.Token(completion.content()),
                    new MessageStreamEvent.Done(
                            new MessageStreamEvent.TokenCount(
                                    saved.getInputTokens(), saved.getOutputTokens(), saved.getTotalTokens()),
                            saved.getCreatedAt()));
        } catch (RuntimeException error) {
            log.error("AI 응답 생성 실패 sessionId={} error={}", turn.sessionId(), error.toString());
            persistFailedQuietly(turn.sessionId());
            refundReservation(turn);
            return List.of(buildErrorEvent(error));
        }
    }

    /**
     * 성공: 사용자 예산은 메시지 입력 추정 + 실측 출력만 계상 (기존 정책 동일).
     * 보정 실패는 삼킨다 — 응답은 이미 저장·전달 대상이므로, 여기서 던지면 정상 완료된 턴이
     * 실패 경로(FAILED 중복 저장 + 이중 정산)로 뒤집힌다. 옛 코드도 보정을 fire-and-forget 으로 돌렸다.
     */
    private void settleOnSuccess(PreparedChatTurn turn, AiChatCompletion completion) {
        if (turn.reservation() == null) {
            return; // Redis 장애 우회(Bypassed) 호출 — 보정할 예약 없음
        }
        try {
            int outputTokens = completion.outputTokens() != null
                    ? completion.outputTokens()
                    : tokenCounter.count(completion.content());
            chatTokenBudget.settle(turn.userId(), turn.reservation(),
                    turn.estimatedMessageInputTokens() + outputTokens);
        } catch (RuntimeException settleError) {
            log.error("토큰 예산 보정 실패(성공 응답은 유지) sessionId={}", turn.sessionId(), settleError);
        }
    }

    /**
     * 실패·게이트 거절: 사용자 과실이 아니므로 예약 전액 환불 (기존 정책 동일).
     * 환불 실패는 삼킨다 — 게이트 거절 경로에서 던지면 429 가 500 으로 둔갑하고,
     * 생성 실패 경로에서 던지면 error 이벤트 전달이 막힌다.
     */
    private void refundReservation(PreparedChatTurn turn) {
        if (turn.reservation() == null) {
            return;
        }
        try {
            chatTokenBudget.settle(turn.userId(), turn.reservation(), 0);
        } catch (RuntimeException refundError) {
            log.error("토큰 예산 환불 실패 sessionId={}", turn.sessionId(), refundError);
        }
    }

    /** 실패 저장이 또 실패해도 error 이벤트 전달을 막지 않는다. */
    private void persistFailedQuietly(Long sessionId) {
        try {
            aiChatMessagePersistService.saveAssistantFailed(sessionId, "", null);
        } catch (RuntimeException persistError) {
            log.error("AI FAILED 메시지 영속화 실패 sessionId={}", sessionId, persistError);
        }
    }

    private TooManyRequestsException tokenBudgetExceeded(Duration retryAfter) {
        AiChatProperties.TokenBudget budget = aiChatProperties.tokenBudget();
        RateLimitInfo info = new RateLimitInfo(
                retryAfter, null, (long) budget.tokensPerWindow(), null, 0L, null, retryAfter);
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
     * 사용자별 폭주 차단 — 상태 무관 단일 카운터.
     * 10초 안에 USER 메시지 5건 이상은 상태와 무관하게 정상 사용이 아니라고 보고 거절한다.
     * 비용 방어의 본체는 토큰 예산(reserveTokenBudget)이고, 이 가드는 초 단위 폭주만 막는다.
     * retryAfter 는 카운트 기간을 그대로 돌려 보낸다 (보수적 추정).
     */
    private void verifyUserMessageRateLimit(Long userId) {
        AiChatProperties.RateLimit limit = aiChatProperties.rateLimit();
        LocalDateTime since = LocalDateTime.now().minusSeconds(limit.countPeriodSeconds());
        long recentCount = aiChatMessageRepository.countRecentUserMessagesByOwner(userId, since);
        if (recentCount >= limit.maxMessageCount()) {
            throw rateLimitExceeded(limit.countPeriodSeconds(), limit.maxMessageCount());
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
