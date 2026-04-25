package com.readum.domain.ai.service;

import com.readum.domain.ai.dto.AiChatCommand;
import com.readum.domain.ai.out.AiChatClient;
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
