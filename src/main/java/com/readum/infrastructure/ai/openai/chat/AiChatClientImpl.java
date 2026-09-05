package com.readum.infrastructure.ai.openai.chat;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.exception.BusinessException;
import com.readum.domain.exception.ExternalApiException;
import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.infrastructure.ai.openai.ChatResponseAuditMapper;
import com.readum.infrastructure.ai.openai.guardrail.ChatInputGuardrail;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRateLimitGuard;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import com.readum.domain.aiChat.out.TokenCounter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatClientImpl implements AiChatClient {

    // 감사 로그용 프롬프트 식별자. reading-assistant-system.st 프롬프트를 바꾸면 버전을 올려
    // 변경 전후의 토큰/지연/품질 변화를 감사 로그에서 구분할 수 있게 한다.
    private static final String PROMPT_TEMPLATE_ID = "reading-assistant-system";
    private static final String PROMPT_TEMPLATE_VERSION = "v1";

    private final ChatClient chatClient;
    // 스트리밍 전용 ChatModel (OpenAiConfig#streamingChatModel). ChatClient 를 거치지 않는 이유와
    // 그 대가(프롬프트 조립·입력 검사를 여기서 직접 해야 함)는 그 빈의 주석에 적혀 있다.
    private final ChatModel streamingChatModel;
    // ChatClient 를 우회하면서 빠진 입력 advisor 두 개와 같은 판정을 하는 로컬 검사기.
    private final ChatInputGuardrail chatInputGuardrail;
    private final AiPromptAuditLogger auditLogger;
    private final OpenAiRateLimitGuard rateLimitGuard;
    private final AiChatProperties aiChatProperties;
    private final TokenCounter tokenCounter;

    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    @Value("classpath:prompts/reading-assistant-system.st")
    private Resource systemPromptResource;

    private String baseSystemPrompt;

    @PostConstruct
    void init() throws IOException {
        // 책 정보·이전 대화 요약을 매 호출마다 덧붙여야 하므로 시스템 프롬프트 파일을 직접 읽어 조립한다.
        // 비스트리밍 chatClient 빈의 defaultSystem 과 같은 파일이며, 그쪽은 이 값으로 덮어쓴다.
        this.baseSystemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public RateLimitPermit acquireRateLimitPermit(AiChatStreamCommand command) {
        String systemPrompt = buildSystemPrompt(command.bookContext(), command.contextSummary());
        // 분당 예산에서 "요청 1 + 추정 토큰" 확보. 사전 단계(요청 스레드)에서 호출되어
        // 거절은 GlobalExceptionHandler 가 429 JSON 으로 변환한다.
        // 계상은 실제 전송량 전체(시스템 프롬프트 + 이력 + 예약 출력) — 사용자 예산과 달리 오버헤드 포함.
        int payloadTokens = tokenCounter.count(systemPrompt);
        for (HistoryMessage historyMessage : command.history()) {
            payloadTokens += tokenCounter.count(historyMessage.content());
        }
        int estimatedTokens = payloadTokens + aiChatProperties.tokenBudget().estimatedOutputTokens();
        // 게이트 계상 내역을 도메인이 들고 다닐 permit 으로 변환한다 — 도메인이 인프라 타입을
        // 모르게 하는 경계 번역. fail-open 통과(계상 없음)는 release 가 no-op 인 Uncounted.
        return rateLimitGuard.acquireOrThrow(chatModel, estimatedTokens)
                .<RateLimitPermit>map(reservation -> new RateLimitPermit.Counted(
                        reservation.model(), reservation.epochMinute(), reservation.estimatedTokens()))
                .orElseGet(RateLimitPermit.Uncounted::new);
    }

    @Override
    public void releaseRateLimitPermit(RateLimitPermit permit) {
        if (permit instanceof RateLimitPermit.Counted counted) {
            rateLimitGuard.compensate(new OpenAiRequestGate.GateReservation(
                    counted.model(), counted.epochMinute(), counted.estimatedTokens()));
        }
    }

    /** 호출 전 acquireRateLimitPermit() 이 선행되어야 한다 — 전역 게이트 검사는 여기서 하지 않는다. */
    @Override
    public AiChatCompletion generate(AiChatStreamCommand command) {
        String systemPrompt = buildSystemPrompt(command.bookContext(), command.contextSummary());

        List<Message> messages = command.history().stream()
                .map(this::toSpringMessage)
                .toList();

        AiPromptAuditEvent baseEvent = AiPromptAuditEvent.started(
                conversationIdHash(command.conversationId()),
                PROMPT_TEMPLATE_ID,
                PROMPT_TEMPLATE_VERSION,
                promptHash(command.history())
        );
        long startNanos = System.nanoTime();

        try {
            ChatResponse chatResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(messages)
                    .call()
                    .chatResponse();
            AiChatCompletion completion = toCompletion(chatResponse);
            auditLogger.success(
                    ChatResponseAuditMapper.applyResult(baseEvent, chatResponse, elapsedMillis(startNanos)));
            return completion;
        } catch (RuntimeException error) {
            logUnexpectedError(error);
            auditLogger.failure(
                    ChatResponseAuditMapper.applyResult(baseEvent, null, elapsedMillis(startNanos)), error);
            throw error;
        }
    }

    /**
     * 토큰 스트리밍. 호출 전 acquireRateLimitPermit() 이 선행되어야 한다.
     *
     * <p>ChatClient 가 아니라 {@code OpenAiChatModel} 을 직접 호출한다(이유는 그 빈의 주석 참조).
     * 그래서 ChatClient 가 해 주던 두 가지를 여기서 직접 한다.
     * <ul>
     *   <li>프롬프트 조립: 시스템 메시지가 맨 앞, 그 뒤에 대화 이력(마지막이 이번 사용자 입력).
     *       ChatClient 의 {@code .system(...) + .messages(...)} 가 만들던 순서와 같다.</li>
     *   <li>로컬 입력 검사: {@link ChatInputGuardrail} 이 입력 advisor 두 개와 같은 판정을 한다.</li>
     * </ul>
     *
     * <p>감사 로그는 호출 1건당 한 번만 남긴다 — 종료 훅이 겹쳐 불려도 먼저 도착한 하나만 기록한다.
     * 성공 기록에는 마지막으로 본 사용량 청크의 응답을 결과로 쓴다.
     */
    @Override
    public Flux<AiChatStreamChunk> generateStream(AiChatStreamCommand command) {
        List<Message> promptMessages = buildPromptMessages(command);

        AiPromptAuditEvent baseEvent = AiPromptAuditEvent.started(
                conversationIdHash(command.conversationId()),
                PROMPT_TEMPLATE_ID,
                PROMPT_TEMPLATE_VERSION,
                promptHash(command.history())
        );
        long startNanos = System.nanoTime();
        AtomicReference<ChatResponse> lastResponseWithUsage = new AtomicReference<>();
        // 정상 종료·오류·취소는 서로 배타적이지 않다. Reactor 는 취소와 종료가 겹칠 수 있고,
        // 세 훅이 이 깃발을 두고 경쟁해 먼저 세운 쪽만 감사 로그를 남긴다(호출 1건 = 감사 1건).
        AtomicBoolean auditRecorded = new AtomicBoolean(false);

        return responseStream(promptMessages)
                .map(chatResponse -> {
                    AiChatStreamChunk chunk = toStreamChunk(chatResponse);
                    if (chunk.hasValidUsage()) {
                        lastResponseWithUsage.set(chatResponse);
                    }
                    return chunk;
                })
                .doOnComplete(() -> {
                    if (auditRecorded.compareAndSet(false, true)) {
                        auditLogger.success(ChatResponseAuditMapper.applyResult(
                                baseEvent, lastResponseWithUsage.get(), elapsedMillis(startNanos)));
                    }
                })
                .doOnError(error -> {
                    if (auditRecorded.compareAndSet(false, true)) {
                        logUnexpectedError(error);
                        auditLogger.failure(ChatResponseAuditMapper.applyResult(
                                baseEvent, null, elapsedMillis(startNanos)), error);
                    }
                })
                // 취소는 상류 기한 초과·종료 등으로 구독이 끊긴 경우다. 아무 기록도 남기지 않으면
                // "호출은 했는데 감사 로그가 없는" 건이 생기므로 실패로 남긴다.
                .doOnCancel(() -> {
                    if (auditRecorded.compareAndSet(false, true)) {
                        auditLogger.failure(
                                ChatResponseAuditMapper.applyResult(
                                        baseEvent, lastResponseWithUsage.get(), elapsedMillis(startNanos)),
                                new CancellationException("AI 응답 스트림 구독이 취소되었습니다."));
                    }
                });
    }

    /** 시스템 메시지가 맨 앞, 그 뒤에 대화 이력. ChatClient 가 조립하던 순서 그대로다. */
    private List<Message> buildPromptMessages(AiChatStreamCommand command) {
        String systemPrompt = buildSystemPrompt(command.bookContext(), command.contextSummary());
        List<Message> promptMessages = new ArrayList<>(command.history().size() + 1);
        promptMessages.add(new SystemMessage(systemPrompt));
        for (HistoryMessage historyMessage : command.history()) {
            promptMessages.add(toSpringMessage(historyMessage));
        }
        return promptMessages;
    }

    /**
     * 모델 응답 스트림.
     *
     * <p>로컬 입력 검사에 걸리면 모델을 부르지 않고(= 외부 호출·과금 없음) 거부 정본 한 건만 흘려보낸다 —
     * 입력 advisor 가 차단하던 때와 같은 모양이라 뒤따르는 감사 기록·청크 처리가 그대로 이어진다.
     *
     * <p><b>주의(후속 결정 대상):</b> 이 거부 응답에는 종료 사유도 사용량도 없다. 즉 응답 모양은 예전과 같지만,
     * 새 정상 완료 판정({@code AiChatGenerationAccumulator})으로 보면 성공이 아니다.
     * 거절을 어디서·어떤 코드로 내보내고 예약을 어떻게 되돌릴지가 정해지기 전까지는
     * 응답 모양을 앞질러 바꾸지 않는다(판정 동등성은 유지, 응답 동등성도 현행 유지).
     *
     * <p>변환 손실이 의심되는 응답은 오류로 바꿔 스트림을 끊는다 — 성공으로 흘려보내지 않기 위해서다.
     */
    private Flux<ChatResponse> responseStream(List<Message> promptMessages) {
        Optional<String> blockedFailureResponse =
                chatInputGuardrail.findBlockedFailureResponse(promptMessages);
        if (blockedFailureResponse.isPresent()) {
            return Flux.just(ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(blockedFailureResponse.get()))))
                    .build());
        }
        return streamingChatModel.stream(new Prompt(promptMessages))
                .map(chatResponse -> {
                    if (isConversionLossSuspected(chatResponse)) {
                        log.error("[AI Chat] 응답 변환 손실 의심 — Spring AI 가 예외를 삼키고 빈 응답으로 바꾼 것으로 보인다");
                        throw new ExternalApiException(AiChatErrorCode.AI_STREAM_INTERRUPTED);
                    }
                    return chatResponse;
                });
    }

    /**
     * Spring AI 2.0.0-M4 의 {@code OpenAiChatModel} 은 스트림 응답을 변환하다 예외가 나면 그것을 로그로만 남기고
     * 내용이 하나도 없는 {@code ChatResponse} 로 바꿔 흘려보낸다(바이트코드 확인: 변환 전체를 감싼
     * {@code catch (Exception)} → {@code new ChatResponse(List.of())}). 그대로 두면 답변 일부가 조용히 빠진
     * 본문을 정상 완료로 저장하게 된다.
     *
     * <p>세 조건을 <b>모두</b> 봐야 정상 청크와 구분된다. 하나만 보면 정상을 오판한다.
     * <ul>
     *   <li>본문(generations) 없음만 보면 — 사용량 전용 마지막 청크({@code choices: []})를 오판한다.</li>
     *   <li>사용량 없음만 보면 — 사용량이 실리지 않는 대부분의 중간 청크를 오판한다.</li>
     *   <li>식별자 없음만 보면 — 식별자를 주지 않는 응답 형태에 기댈 수 없다.</li>
     * </ul>
     *
     * <p><b>한계:</b> 이 검사는 "변환이 통째로 실패한 경우"만 걸러낸다. 변환이 부분적으로만 어긋나 내용이 남아 있는
     * 손실은 걸러내지 못하고, 본문이 비지 않았다는 사실만으로 중간 손실이 없다고 말할 수도 없다.
     * 검출할 수 있는 것과 없는 것의 실측 구분은 {@code OpenAiChatModelStreamContractTest} 에 남아 있다.
     */
    boolean isConversionLossSuspected(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return true;
        }
        if (chatResponse.getResult() != null) {
            return false;
        }
        if (toStreamChunk(chatResponse).hasValidUsage()) {
            return false;
        }
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        String responseId = metadata == null ? null : metadata.getId();
        return responseId == null || responseId.isBlank();
    }

    // 본문 조각뿐 아니라 종료 사유(finish_reason)와 사용량도 그대로 DTO 로 옮긴다 — 생성 성공 판정에 둘 다 필요하다.
    // 본문이 빈 청크(종료 사유 전용·사용량 전용)는 정상 모양이므로 걸러내지 않고 그대로 내보낸다.
    // 매핑 계약(종료 사유·사용량 보존)을 같은 패키지의 테스트가 직접 확인할 수 있도록 package-private.
    AiChatStreamChunk toStreamChunk(ChatResponse chatResponse) {
        Generation result = Optional.ofNullable(chatResponse).map(ChatResponse::getResult).orElse(null);
        String delta = Optional.ofNullable(result)
                .map(generation -> generation.getOutput())
                .map(output -> output.getText())
                .orElse("");
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        Usage usage = Optional.ofNullable(metadata).map(ChatResponseMetadata::getUsage).orElse(null);
        return new AiChatStreamChunk(
                delta,
                extractFinishReason(result),
                usage == null ? null : toIntOrNull(usage.getPromptTokens()),
                usage == null ? null : toIntOrNull(usage.getCompletionTokens()),
                usage == null ? null : toIntOrNull(usage.getTotalTokens())
        );
    }

    // Spring AI 는 종료 사유가 없는 중간 청크에 빈 문자열을 채워 보낸다. 빈 값은 "사유 없음"(null)으로 통일해
    // 소비자가 빈 문자열과 null 을 따로 다루지 않게 한다. 값이 있으면 원본 그대로 보존한다 (STOP / LENGTH 등).
    private String extractFinishReason(Generation result) {
        String finishReason = Optional.ofNullable(result)
                .map(Generation::getMetadata)
                .map(ChatGenerationMetadata::getFinishReason)
                .orElse(null);
        return (finishReason == null || finishReason.isBlank()) ? null : finishReason;
    }

    private AiChatCompletion toCompletion(ChatResponse chatResponse) {
        String text = Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(result -> result.getOutput())
                .map(output -> output.getText())
                .orElse("");
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        Usage usage = Optional.ofNullable(metadata).map(ChatResponseMetadata::getUsage).orElse(null);
        return new AiChatCompletion(
                text,
                usage == null ? null : toIntOrNull(usage.getPromptTokens()),
                usage == null ? null : toIntOrNull(usage.getCompletionTokens()),
                usage == null ? null : toIntOrNull(usage.getTotalTokens()),
                extractRateLimit(metadata)
        );
    }

    private String conversationIdHash(Long conversationId) {
        return conversationId == null ? null : auditLogger.sha256(String.valueOf(conversationId));
    }

    // 직전 USER 질문 원문은 저장하지 않고 해시(지문)만 남겨 동일 질문 반복 등을 식별할 수 있게 한다.
    private String promptHash(List<HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        return auditLogger.sha256(history.get(history.size() - 1).content());
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 시스템 프롬프트를 조립한다: [base + 책 정보] + [누적 요약]. 프롬프트 캐시를 고려한 배치(고정 → 저변동):
     * base·책 정보는 세션 내 고정, 누적 요약은 요약 갱신 때만 변동한다. 매 턴 변동하는 최근 원문 대화는 messages 로 따로 실린다.
     */
    private String buildSystemPrompt(AiChatStreamCommand.BookContext ctx, String contextSummary) {
        StringBuilder sb = new StringBuilder(baseSystemPrompt);
        if (ctx != null) {
            sb.append("\n\n# 대화 대상 도서\n");
            sb.append("제목: ").append(ctx.title()).append("\n");
            if (ctx.authors() != null && !ctx.authors().isBlank()) {
                sb.append("저자: ").append(ctx.authors()).append("\n");
            }
            if (ctx.publisher() != null && !ctx.publisher().isBlank()) {
                sb.append("출판사: ").append(ctx.publisher()).append("\n");
            }
        }
        if (contextSummary != null && !contextSummary.isBlank()) {
            sb.append("\n\n# 이전 대화 요약\n");
            sb.append("아래는 지금까지 나눈 대화의 요약이다. 최근 대화 원문은 이어지는 메시지로 제공된다.\n");
            sb.append(contextSummary).append("\n");
        }
        return sb.toString();
    }

    private Message toSpringMessage(HistoryMessage history) {
        return switch (history.role()) {
            case USER -> new UserMessage(history.content());
            case ASSISTANT -> new AssistantMessage(history.content());
        };
    }

    private AiChatCompletion.RateLimitSnapshot extractRateLimit(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        // Spring AI 2.0.0-M4(milestone) 의 RateLimit getter 동작이 안정 보장되지 않아
        // RuntimeException 으로 안전 폴백한다. 추출 실패 시 null 로 두고 응답 처리는 계속 진행.
        try {
            RateLimit rateLimit = metadata.getRateLimit();
            if (rateLimit == null) {
                return null;
            }
            return new AiChatCompletion.RateLimitSnapshot(
                    rateLimit.getRequestsLimit(),
                    rateLimit.getRequestsRemaining(),
                    rateLimit.getRequestsReset(),
                    rateLimit.getTokensLimit(),
                    rateLimit.getTokensRemaining(),
                    rateLimit.getTokensReset()
            );
        } catch (RuntimeException ex) {
            log.debug("Rate limit 메타데이터 추출 실패", ex);
            return null;
        }
    }

    private Integer toIntOrNull(Number number) {
        return number == null ? null : number.intValue();
    }

    // 429 / quota / 기타 4xx-5xx 분류는 OpenAiResponseErrorHandler 가 가장 낮은 계층에서
    // 처리한다 (HTTP status / 응답 헤더 / body JSON 모두 typed 하게 접근 가능한 곳).
    // 여기서는 미분류 예외(네트워크 끊김 등) 만 ERROR 로 남기고, 이미 도메인 예외로 분류된 건 그대로 흘려보낸다.
    private void logUnexpectedError(Throwable error) {
        if (error instanceof BusinessException) {
            return;
        }
        log.error("[AI Chat] OpenAI API 호출 실패", error);
    }
}
