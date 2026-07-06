package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private final AiChatMessageRepository aiChatMessageRepository;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;
    private final InputModerationClient inputModerationClient;

    public Flux<MessageStreamEvent> execute(SendMessageCommand command) {
        String normalizedContent = validateAndStripContent(command.content());
        Long sessionId = command.sessionId();
        Long userId = command.userId();

        // 사전 단계: rate-limit 검사 / 이력 조회 / 입력 moderation / USER 메시지 영속화는 모두 SSE 시작 전에 끝난다.
        // 여기서 던진 예외는 SSE 이전에 GlobalExceptionHandler 가 처리해 4XX/5XX JSON 응답으로 나간다.
        verifyUserMessageRateLimit(userId);

        // 이력만 조회(USER 미저장). 세션 검증은 여기서 끝난다.
        AiChatMessagePersistService.MessageLoadResult loaded =
                aiChatMessagePersistService.loadHistory(sessionId, userId);

        AiChatStreamCommand.BookContext bookContext = resolveBookContext(loaded.userBookId());

        // 입력 가드레일: SSE 시작 전 동기 실행. 거부 응답이 일반 토큰으로 흘러 저장 트리거를 발동하는 문제를 근본 차단.
        InputModerationResult moderation = inputModerationClient.check(normalizedContent, bookContext);
        switch (moderation.status()) {
            case BLOCKED -> {
                aiChatMessagePersistService.recordRejectedUserMessage(sessionId, normalizedContent);
                log.warn("[Guardrail] 입력 차단 sessionId={} userId={} categories={}",
                        sessionId, userId, moderation.flaggedCategories());
                throw new BadRequestException(AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT);
            }
            case UNAVAILABLE -> {
                // 외부 Moderation API 장애(fail-closed). USER 메시지를 저장하지 않고 503.
                throw new ServiceUnavailableException(AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE);
            }
            case PASSED -> {
                // 통과: COMPLETED 저장 후 스트림 시작.
            }
        }

        // 통과한 경우에만 USER 메시지를 COMPLETED 로 저장(턴 카운트 포함).
        aiChatMessagePersistService.recordUserMessage(sessionId, userId, normalizedContent);

        List<HistoryMessage> withCurrent = new ArrayList<>(loaded.history().size() + 1);
        withCurrent.addAll(loaded.history());
        withCurrent.add(new HistoryMessage(HistoryMessage.Role.USER, normalizedContent));

        StringBuilder contentBuffer = new StringBuilder();
        AtomicReference<AiChatChunk.Completion> completionRef = new AtomicReference<>();

        return aiChatClient.stream(new AiChatStreamCommand(sessionId, withCurrent, bookContext))
                .concatMap(chunk -> bufferAndConvertChunk(chunk, contentBuffer, completionRef))
                // doOnCancel 위치 주의: concatMap 직후, concatWith 앞.
                // Phase 1 (LLM 스트리밍 중) 에 클라이언트가 끊기면 여기로 cancel 이 전파돼 fire.
                // Phase 2 (concatWith 의 saveAssistantSuccess JDBC 중) 에 cancel 이 들어와도
                // concatMap 이 이미 onComplete 된 이후라 cancel 이 이 위치까지 올라오지 않는다.
                // 즉 success path 와 cancel path 가 동시에 영속화하는 케이스가 구조적으로 차단된다.
                .doOnCancel(() -> persistOnClientCancel(sessionId, contentBuffer.toString(), completionRef.get()))
                .concatWith(Mono.defer(() -> persistAssistantMessageAndEmitDone(sessionId, contentBuffer.toString(), completionRef.get())))
                .onErrorResume(error -> persistFailedAssistantMessageAndEmitError(sessionId, contentBuffer.toString(), completionRef.get(), error));
    }

    private Flux<MessageStreamEvent> bufferAndConvertChunk(
            AiChatChunk chunk,
            StringBuilder contentBuffer,
            AtomicReference<AiChatChunk.Completion> completionRef
    ) {
        return switch (chunk) {
            case AiChatChunk.Token token -> {
                // 누적된 응답 텍스트는 종료 후 영속화에 쓰고, delta 는 외부에 즉시 흘려 보낸다.
                contentBuffer.append(token.delta());
                yield Flux.just(new MessageStreamEvent.Token(token.delta()));
            }
            case AiChatChunk.Completion completion -> {
                // Done 이벤트는 ASSISTANT 영속화 후 createdAt·tokenCount 까지 채워서 만들어야 하므로
                // 여기서는 메타만 잡아두고 외부로는 emit 하지 않는다 (concatWith 의 finalize 단계에서 발행).
                completionRef.set(completion);
                yield Flux.empty();
            }
        };
    }

    private Mono<MessageStreamEvent> persistAssistantMessageAndEmitDone(
            Long sessionId, String content, AiChatChunk.Completion completion
    ) {
        return Mono.fromCallable(() -> aiChatMessagePersistService.saveAssistantSuccess(sessionId, content, completion))
                .subscribeOn(Schedulers.boundedElastic())
                .map(saved -> new MessageStreamEvent.Done(
                        new MessageStreamEvent.TokenCount(
                                saved.getInputTokens(),
                                saved.getOutputTokens(),
                                saved.getTotalTokens()
                        ),
                        saved.getCreatedAt()
                ));
    }

    private Flux<MessageStreamEvent> persistFailedAssistantMessageAndEmitError(
            Long sessionId, String content, AiChatChunk.Completion completion, Throwable error
    ) {
        log.error("AI 스트림 비정상 종료 sessionId={} error={}", sessionId, error.toString());
        // 클라이언트에 error 이벤트를 먼저 보내고, FAILED 영속화는 별도 스레드에서 결과를 기다리지 않고 실행.
        // 영속화 완료를 기다린 뒤 emit 하면(`.thenMany`) DB JDBC 가 느릴수록 사용자가 더 오래
        // "응답 없음" 으로 보이게 된다. 영속화가 실패해도 클라이언트에 영향이 가지 않도록
        // 예외는 잡아서 로그만 남기고 외부로 다시 던지지 않는다.
        // 같은 파일의 persistOnClientCancel 과 동일한 비동기 패턴.
        Mono.fromRunnable(() -> {
                    try {
                        aiChatMessagePersistService.saveAssistantFailed(sessionId, content, completion);
                    } catch (RuntimeException ex) {
                        log.error("AI FAILED 메시지 영속화 실패 sessionId={}", sessionId, ex);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return Flux.just(buildErrorEvent(error));
    }

    /**
     * 클라이언트 disconnect (탭 닫힘 / 네트워크 끊김) 로 SSE writer 가 cancel 을 보낸 경우의 영속화.
     * 분기 정책:
     * - completion != null            : LLM 응답이 끝까지 도착했고 토큰 메타도 받았다.
     *                                   Done 이벤트만 전송 못 했을 뿐 의미상 정상 종료라 COMPLETED 저장.
     * - completion == null && content != "" : LLM 응답 도중 끊김. 부분 응답을 FAILED 로 보존
     *                                          (컨텍스트 윈도우에서 자동 제외됨).
     * - completion == null && content == "" : 첫 토큰도 받기 전 끊김. 저장할 의미 없으므로 skip.
     *
     * 클라이언트는 이미 떠났으므로 영속화 실패 시에도 던지지 않고 로그만 남긴다.
     */
    private void persistOnClientCancel(Long sessionId, String content, AiChatChunk.Completion completion) {
        if (completion == null && content.isEmpty()) {
            log.info("AI 클라이언트 disconnect (응답 0byte) - 영속화 skip sessionId={}", sessionId);
            return;
        }
        Mono.fromRunnable(() -> {
                    try {
                        if (completion != null) {
                            aiChatMessagePersistService.saveAssistantSuccess(sessionId, content, completion);
                        } else {
                            aiChatMessagePersistService.saveAssistantFailed(sessionId, content, null);
                        }
                    } catch (RuntimeException ex) {
                        log.error("AI 클라이언트 disconnect 후 ASSISTANT 메시지 영속화 실패 sessionId={}", sessionId, ex);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
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
     * 사용자별 호출 폭주 차단. 정상/거부 카운터를 각자 독립 한도와 비교한다.
     * - 정상: 최근 countPeriodSeconds 초 동안 COMPLETED USER 메시지가 maxMessageCount 회 이상이면 거절.
     * - 거부: 최근 rejectedCountPeriodSeconds 초 동안 REJECTED USER 메시지가 rejectedMaxMessageCount 회 이상이면 거절.
     * 두 한도는 독립이므로 moderation 의 false-positive 가 폭증해도 정상 채팅(정상 카운트 0) 은 막히지 않고,
     * 어뷰즈(의도적 거부 입력 반복) 만 거부 카운터로 차단된다.
     * 정밀 정책(사용자 tier 별 한도, 분산 카운터 등) 은 트래픽 데이터가 쌓인 후 도입 예정이며,
     * 현재 구현은 OpenAI 비용 폭주(클라이언트 무한 retry, 키 유출) 방어선이다.
     * 비용 방어선 목적이므로 retryAfter 는 카운트 기간을 그대로 돌려 보낸다 (보수적 추정).
     */
    private void verifyUserMessageRateLimit(Long userId) {
        AiChatProperties.RateLimit limit = aiChatProperties.rateLimit();

        LocalDateTime normalSince = LocalDateTime.now().minusSeconds(limit.countPeriodSeconds());
        long normalCount = aiChatMessageRepository.countRecentUserMessagesByOwner(userId, normalSince);
        if (normalCount >= limit.maxMessageCount()) {
            throw rateLimitExceeded(limit.countPeriodSeconds(), limit.maxMessageCount());
        }

        LocalDateTime rejectedSince = LocalDateTime.now().minusSeconds(limit.rejectedCountPeriodSeconds());
        long rejectedCount = aiChatMessageRepository.countRecentRejectedMessagesByOwner(userId, rejectedSince);
        if (rejectedCount >= limit.rejectedMaxMessageCount()) {
            throw rateLimitExceeded(limit.rejectedCountPeriodSeconds(), limit.rejectedMaxMessageCount());
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
