package com.readum.infrastructure.ai.openai.chat;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.exception.BusinessException;
import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.infrastructure.ai.openai.ChatResponseAuditMapper;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRateLimitGuard;
import com.readum.domain.aiChat.out.TokenCounter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
        // ChatClient bean 의 defaultSystem 과 동일한 파일이지만,
        // 책 정보를 동적으로 덧붙이기 위해 직접 로드해서 .system() 오버라이드에 사용한다.
        this.baseSystemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public Flux<AiChatChunk> stream(AiChatStreamCommand command) {
        String systemPrompt = buildSystemPrompt(command.bookContext(), command.contextSummary());

        // 전역 게이트: 조립 시점(동기) 검사 — 여기서 던지면 SSE 시작 전에 GlobalExceptionHandler 가 429 로 변환한다.
        // Flux 체인 안으로 옮기면 mid-stream 에러가 되므로 반드시 이 위치를 유지할 것.
        // 계상은 실제 전송량 전체(시스템 프롬프트 + 이력 + 예약 출력) — 사용자 예산과 달리 오버헤드 포함.
        int payloadTokens = tokenCounter.count(systemPrompt);
        for (HistoryMessage historyMessage : command.history()) {
            payloadTokens += tokenCounter.count(historyMessage.content());
        }
        int estimatedTokens = payloadTokens
                + aiChatProperties.tokenBudget().estimatedOutputTokens();
        rateLimitGuard.acquireOrThrow(chatModel, estimatedTokens);

        List<Message> messages = command.history().stream()
                .map(this::toSpringMessage)
                .toList();

        // 감사 로그: 호출 직전에 알 수 있는 식별 정보만 먼저 잡아두고, 모델/토큰/지연은 종료 시점에 덧채운다.
        AiPromptAuditEvent baseEvent = AiPromptAuditEvent.started(
                conversationIdHash(command.conversationId()),
                PROMPT_TEMPLATE_ID,
                PROMPT_TEMPLATE_VERSION,
                promptHash(command.history())
        );
        long startNanos = System.nanoTime();
        // 토큰 사용량은 스트림 마지막 청크에만 실려 오므로, 그 청크의 ChatResponse 를 잡아 둔다.
        AtomicReference<ChatResponse> usageResponseRef = new AtomicReference<>();

        return chatClient.prompt()
                .system(systemPrompt)
                .messages(messages)
                .stream()
                .chatResponse()
                .concatMap(chatResponse -> toChunks(chatResponse, usageResponseRef))
                .doOnComplete(() -> auditLogger.success(
                        ChatResponseAuditMapper.applyResult(baseEvent, usageResponseRef.get(), elapsedMillis(startNanos))))
                .doOnError(error -> {
                    logUnexpectedError(error);
                    auditLogger.failure(
                            ChatResponseAuditMapper.applyResult(baseEvent, usageResponseRef.get(), elapsedMillis(startNanos)),
                            error);
                });
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

    private Flux<AiChatChunk> toChunks(ChatResponse chatResponse, AtomicReference<ChatResponse> usageResponseRef) {
        String text = Optional.ofNullable(chatResponse.getResult())
                .map(result -> result.getOutput())
                .map(output -> output.getText())
                .orElse("");
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = Optional.ofNullable(metadata).map(ChatResponseMetadata::getUsage).orElse(null);

        // application-{profile}.yml 의 spring.ai.openai.chat.options.stream-usage: true 설정이 켜져 있을 때만,
        // 마지막 청크에 누적 토큰 사용량(usage)이 채워져 도착한다. 이것으로 스트림 종료 시점을 식별한다.
        boolean hasUsage = usage != null
                && usage.getTotalTokens() != null
                && usage.getTotalTokens() > 0;

        if (hasUsage) {
            // 종료 시점 감사 로그가 토큰/모델 메타데이터를 읽을 수 있도록 이 청크의 응답을 보관한다.
            usageResponseRef.set(chatResponse);
            AiChatChunk completion = new AiChatChunk.Completion(
                    toIntOrNull(usage.getPromptTokens()),
                    toIntOrNull(usage.getCompletionTokens()),
                    toIntOrNull(usage.getTotalTokens()),
                    extractRateLimit(metadata)
            );
            log.info("[Stream Token Usage] Prompt tokens: {}, Completion tokens: {}, Total tokens: {}",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            return text.isEmpty()
                    ? Flux.just(completion)
                    : Flux.just(new AiChatChunk.Token(text), completion);
        }
        return text.isEmpty() ? Flux.empty() : Flux.just(new AiChatChunk.Token(text));
    }

    private AiChatChunk.RateLimitSnapshot extractRateLimit(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        // Spring AI 2.0.0-M4(milestone) 의 RateLimit getter 동작이 안정 보장되지 않아
        // RuntimeException 으로 안전 폴백한다. 추출 실패 시 null 로 두고 스트림은 계속 진행.
        try {
            RateLimit rateLimit = metadata.getRateLimit();
            if (rateLimit == null) {
                return null;
            }
            return new AiChatChunk.RateLimitSnapshot(
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
        log.error("[Stream] OpenAI API 호출 실패", error);
    }
}
