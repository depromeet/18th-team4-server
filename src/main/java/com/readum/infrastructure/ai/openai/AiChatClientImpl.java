package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.dto.AiChatCommand;
import com.readum.domain.aiChat.out.AiChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatClientImpl implements AiChatClient {

    private final ChatClient chatClient;

    @Override
    public Flux<String> stream(AiChatCommand command) {
        return chatClient.prompt()
                .user(command.message())
                .stream()
                .chatResponse()
                .doOnNext(this::logUsageIfPresent)
                .doOnError(e -> log.error("[Stream] OpenAI API 호출 실패", e))
                .mapNotNull(chatResponse -> Optional.ofNullable(chatResponse.getResult())
                        .map(Generation::getOutput)
                        .map(AbstractMessage::getText)
                        .orElse(null));
    }

    private void logUsageIfPresent(ChatResponse chatResponse) {
        Optional.of(chatResponse.getMetadata())
                .map(ChatResponseMetadata::getUsage)
                .filter(usage -> usage.getTotalTokens() > 0)
                .ifPresent(usage -> log.info("[Stream Token Usage] Prompt tokens: {}, Completion tokens: {}, Total tokens: {}",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens()));
    }
}
