package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class AiStreamChatService {

    private final ChatClient chatClient;

    public Flux<String> stream(AiChatCommand command) {
        return chatClient.prompt()
                .user(command.message())
                .stream()
                .content();
    }
}
