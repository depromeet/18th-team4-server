package com.readum.domain.ai.out;

import com.readum.domain.ai.dto.AiChatCommand;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    Flux<String> stream(AiChatCommand command);
}
