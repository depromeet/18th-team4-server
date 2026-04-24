package com.readwith.domain.ai.out;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.dto.AiChatResult;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    AiChatResult chat(AiChatCommand command);

    Flux<String> stream(AiChatCommand command);
}
