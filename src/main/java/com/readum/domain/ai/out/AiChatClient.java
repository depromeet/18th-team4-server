package com.readum.domain.ai.out;

import com.readum.domain.ai.dto.AiChatCommand;
import com.readum.domain.ai.dto.AiChatResult;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    AiChatResult chat(AiChatCommand command);

    Flux<String> stream(AiChatCommand command);
}
