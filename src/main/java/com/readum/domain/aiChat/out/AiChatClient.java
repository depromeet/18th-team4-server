package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    /**
     * OpenAI 전역 게이트 통과를 확보한다. 반드시 generate() 전, SSE 시작 전(사전 단계)에 호출해
     * 거절이 HTTP 429 JSON 으로 나가게 한다. 거절 시 TooManyRequestsException 을 던진다.
     */
    void acquireRateLimitPermit(AiChatStreamCommand command);

    /** 비스트리밍 동기 호출. 완성 응답을 반환하고, 실패는 예외로 던진다. 호출 전 acquireRateLimitPermit() 이 선행되어야 한다. */
    AiChatCompletion generate(AiChatStreamCommand command);

    /** @deprecated 전환 완료 후 Task 6 에서 제거한다. */
    @Deprecated
    Flux<AiChatChunk> stream(AiChatStreamCommand command);
}
