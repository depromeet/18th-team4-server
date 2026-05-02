package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    Flux<AiChatChunk> stream(AiChatStreamCommand command);
}
