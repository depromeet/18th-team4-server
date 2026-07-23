package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    /** 비스트리밍 동기 호출. 완성 응답을 반환하고, 실패는 예외로 던진다. */
    AiChatCompletion generate(AiChatStreamCommand command);

    /** @deprecated 전환 완료 후 Task 6 에서 제거한다. */
    @Deprecated
    Flux<AiChatChunk> stream(AiChatStreamCommand command);
}
