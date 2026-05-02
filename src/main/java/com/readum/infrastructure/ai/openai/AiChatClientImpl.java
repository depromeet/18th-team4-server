package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.exception.TooManyRequestsException;
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
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatClientImpl implements AiChatClient {

    private final ChatClient chatClient;

    @Override
    public Flux<AiChatChunk> stream(AiChatStreamCommand command) {
        List<Message> messages = command.history().stream()
                .map(this::toSpringMessage)
                .toList();

        return chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .concatMap(this::toChunks)
                .onErrorMap(this::mapRateLimitException);
    }

    private Message toSpringMessage(HistoryMessage history) {
        return switch (history.role()) {
            case USER -> new UserMessage(history.content());
            case ASSISTANT -> new AssistantMessage(history.content());
        };
    }

    private Flux<AiChatChunk> toChunks(ChatResponse chatResponse) {
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

    private Throwable mapRateLimitException(Throwable error) {
        // Spring AI 가 OpenAI 의 모든 4xx/5xx 를 NonTransientAiException 으로 일괄 wrap 하기 때문에,
        // HTTP 상태 코드를 직접 볼 수 없다. 메시지 본문의 키워드("429"/"rate"/"quota")로 rate limit 만 식별해
        // TooManyRequestsException 으로 변환 → GlobalExceptionHandler 에서 HTTP 429 로 응답.
        // 그 외 예외는 그대로 흘려보내 SendService 가 적절한 ErrorCode 로 분류한다.
        if (error instanceof NonTransientAiException && isRateLimitMessage(error.getMessage())) {
            log.warn("OpenAI Rate limit 감지 — 429 로 변환: {}", error.getMessage());
            return new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_EXCEEDED);
        }
        log.error("[Stream] OpenAI API 호출 실패", error);
        return error;
    }

    private boolean isRateLimitMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("429") || lower.contains("rate") || lower.contains("quota");
    }
}
