package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.out.AiChatClient;
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
