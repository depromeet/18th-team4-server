package com.readum.domain.aiChat.dto;

import java.time.Duration;

public sealed interface AiChatChunk permits AiChatChunk.Token, AiChatChunk.Completion {

    record Token(String delta) implements AiChatChunk {}

    record Completion(
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            RateLimitSnapshot rateLimit
    ) implements AiChatChunk {}

    /**
     * 정상 응답에 부착되는 OpenAI rate limit 메타데이터 (429 응답 헤더와는 별개).
     * 본 PR 에서는 SSE 로 노출하지 않고 로깅/모니터링 목적으로 캐리만 한다.
     * X-RateLimit-* 헤더 패스스루는 Spring AI 추상화 한계로 후속 작업에 분리.
     */
    record RateLimitSnapshot(
            Long requestsLimit,
            Long requestsRemaining,
            Duration requestsReset,
            Long tokensLimit,
            Long tokensRemaining,
            Duration tokensReset
    ) {
    }
}
