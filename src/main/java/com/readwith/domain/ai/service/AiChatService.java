package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.dto.AiChatResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final ChatClient chatClient;

    public AiChatResult execute(AiChatCommand command) {
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

    private void logUsage(ChatResponse chatResponse) {
        Optional.ofNullable(chatResponse)
                .map(ChatResponse::getMetadata)
                .map(metadata -> metadata.getUsage())
                .filter(usage -> {
                    usage.getTotalTokens();
                    return usage.getTotalTokens() > 0;
                })
                .ifPresent(usage -> log.info(" [Sync Token Usage] Prompt tokens: {}, Completion tokens: {}, Total tokens: {}",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens()));
    }
}
