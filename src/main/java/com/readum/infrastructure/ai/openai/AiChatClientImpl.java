package com.readum.infrastructure.ai.openai;

import com.readum.domain.ai.dto.AiChatCommand;
import com.readum.domain.ai.dto.AiChatResult;
import com.readum.domain.ai.out.AiChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatClientImpl implements AiChatClient {

    private final ChatClient chatClient;

    @Override
    public AiChatResult chat(AiChatCommand command) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(command.message())
                .call()
                .chatResponse();

        logUsage(chatResponse);

        String answer = Optional.ofNullable(chatResponse)
                .map(ChatResponse::getResult)
                .map(result -> result.getOutput())
                .map(output -> output.getText())
                .orElse("");

        return new AiChatResult(answer);
    }

    @Override
    public Flux<String> stream(AiChatCommand command) {
        return chatClient.prompt()
                .user(command.message())
                .stream()
                .chatResponse()
                .doOnNext(this::logUsageIfPresent)
                .doOnError(e -> log.error("[Stream] OpenAI API 호출 실패", e))
                .mapNotNull(chatResponse -> Optional.ofNullable(chatResponse.getResult())
                        .map(result -> result.getOutput())
                        .map(output -> output.getText())
                        .orElse(null));
    }

    private void logUsage(ChatResponse chatResponse) {
        Optional.ofNullable(chatResponse)
                .map(ChatResponse::getMetadata)
                .map(metadata -> metadata.getUsage())
                .filter(usage -> usage.getTotalTokens() > 0)
                .ifPresent(usage -> log.info("[Sync Token Usage] Prompt tokens: {}, Completion tokens: {}, Total tokens: {}",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens()));
    }

    private void logUsageIfPresent(ChatResponse chatResponse) {
        Optional.ofNullable(chatResponse.getMetadata())
                .map(metadata -> metadata.getUsage())
                .filter(usage -> usage.getTotalTokens() > 0)
                .ifPresent(usage -> log.info("[Stream Token Usage] Prompt tokens: {}, Completion tokens: {}, Total tokens: {}",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens()));
    }
}
