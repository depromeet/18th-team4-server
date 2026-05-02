package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatMessageSendService {

    private final AiChatMessagePersistService persistService;
    private final AiChatClient aiChatClient;
    private final AiChatProperties aiChatProperties;

    public Flux<MessageStreamEvent> execute(SendMessageCommand command) {
        String normalizedContent = validateAndStripContent(command.content());
        Long sessionId = command.sessionId();

        // 사전 단계: 검증 / 이전 이력 조회 / USER 메시지 영속화는 PersistService 가 단일 트랜잭션으로 처리.
        // LLM 호출 결과와 무관하게 사용자 메시지를 보존해야 하므로(요구사항) 스트림 시작 전에 commit 한다.
        // 여기서 던진 예외는 SSE 이전에 GlobalExceptionHandler 가 처리해 4XX JSON 응답으로 나간다.
        List<HistoryMessage> previousHistory = persistService.loadHistoryAndRecordUserMessage(
                sessionId, command.userId(), normalizedContent
        );

        List<HistoryMessage> withCurrent = new ArrayList<>(previousHistory.size() + 1);
        withCurrent.addAll(previousHistory);
        withCurrent.add(new HistoryMessage(HistoryMessage.Role.USER, normalizedContent));

        StringBuilder buffer = new StringBuilder();
        AtomicReference<AiChatChunk.Completion> completion = new AtomicReference<>();

        return aiChatClient.stream(new AiChatStreamCommand(withCurrent))
                .concatMap(chunk -> bufferAndConvertChunk(chunk, buffer, completion))
                .concatWith(Mono.defer(() -> persistAssistantMessageAndEmitDone(sessionId, buffer.toString(), completion.get())))
                .onErrorResume(error -> persistFailedAssistantMessageAndEmitError(sessionId, buffer.toString(), completion.get(), error));
    }

    private Flux<MessageStreamEvent> bufferAndConvertChunk(
            AiChatChunk chunk,
            StringBuilder buffer,
            AtomicReference<AiChatChunk.Completion> completion
    ) {
        return switch (chunk) {
            case AiChatChunk.Token token -> {
                // 누적 텍스트는 종료 후 영속화에 쓰고, delta 는 외부에 즉시 흘려 보낸다.
                buffer.append(token.delta());
                yield Flux.just(new MessageStreamEvent.Token(token.delta()));
            }
            case AiChatChunk.Completion meta -> {
                // Done 이벤트는 ASSISTANT 영속화 후 messageId/createdAt 까지 채워서 만들어야 하므로
                // 여기서는 메타만 잡아두고 외부로는 emit 하지 않는다 (concatWith 의 finalize 단계에서 발행).
                completion.set(meta);
                yield Flux.empty();
            }
        };
    }

    private Mono<MessageStreamEvent> persistAssistantMessageAndEmitDone(
            Long sessionId, String accumulated, AiChatChunk.Completion meta
    ) {
        return Mono.fromCallable(() -> persistService.saveAssistantSuccess(sessionId, accumulated, meta))
                .subscribeOn(Schedulers.boundedElastic())
                .map(saved -> new MessageStreamEvent.Done(
                        saved.getId(),
                        new MessageStreamEvent.TokenCount(
                                saved.getInputTokens(),
                                saved.getOutputTokens(),
                                saved.getTotalTokens()
                        ),
                        saved.getCreatedAt()
                ));
    }

    private Flux<MessageStreamEvent> persistFailedAssistantMessageAndEmitError(
            Long sessionId, String partial, AiChatChunk.Completion meta, Throwable error
    ) {
        log.warn("AI 스트림 비정상 종료 sessionId={} error={}", sessionId, error.toString());
        // 영속화 자체가 실패해도 클라이언트에게는 반드시 error 이벤트를 보내야 하므로
        // RuntimeException 을 swallow 하고 로그만 남긴다.
        return Mono.fromRunnable(() -> {
                    try {
                        persistService.saveAssistantFailed(sessionId, partial, meta);
                    } catch (RuntimeException ex) {
                        log.error("AI FAILED 메시지 영속화 실패 sessionId={}", sessionId, ex);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.just(buildErrorEvent(error)));
    }

    private MessageStreamEvent.Error buildErrorEvent(Throwable error) {
        AiChatErrorCode code = toErrorCode(error);
        return new MessageStreamEvent.Error(code.name(), code.getMessage());
    }

    private AiChatErrorCode toErrorCode(Throwable error) {
        // 분류 순서가 중요. TooManyRequestsException 도 BusinessException 의 자식이므로
        // 더 구체적인 타입을 먼저 매칭해야 한다.
        if (error instanceof TooManyRequestsException) {
            return AiChatErrorCode.AI_RATE_LIMIT_EXCEEDED;
        }
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
        // trim() 대신 strip() 사용: NBSP(U+00A0) / 한자 공백(U+3000) 등 한국어 IME 에서
        // 잘못 들어올 수 있는 유니코드 공백까지 제거하기 위함.
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            throw new BadRequestException(AiChatErrorCode.MESSAGE_CONTENT_BLANK);
        }
        if (stripped.length() > aiChatProperties.message().maxContentLength()) {
            throw new BadRequestException(AiChatErrorCode.MESSAGE_CONTENT_TOO_LONG);
        }
        return stripped;
    }
}
