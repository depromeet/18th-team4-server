package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamChatService {

    private final ChatClient chatClient;

    public Flux<String> stream(AiChatCommand command) {
        return chatClient.prompt()
                .user(command.message())
                .stream()
                .chatResponse()
                .doOnNext(this::logUsageIfPresent)
                .mapNotNull(chatResponse -> Optional.ofNullable(chatResponse.getResult())
                        .map(result -> result.getOutput())
                        .map(output -> output.getText())
                        .orElse(null));
    }

    private void logUsageIfPresent(ChatResponse chatResponse) {
        Optional.ofNullable(chatResponse.getMetadata())
                .map(metadata -> metadata.getUsage())
                .filter(usage -> {
                    usage.getTotalTokens();
                    return usage.getTotalTokens() > 0;
                })
                .ifPresent(usage -> log.info(" [Stream Token Usage] Prompt tokens: {}, Completion tokens: {}, Total tokens: {}",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens()));
    }
}
