package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatCommand;
import com.readum.domain.aiChat.out.AiChatClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class AiStreamChatService {

    private final AiChatClient aiChatClient;

    public Flux<String> stream(AiChatCommand command) {
        return aiChatClient.stream(command);
    }
}
