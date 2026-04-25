package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatCommand;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    Flux<String> stream(AiChatCommand command);
}
