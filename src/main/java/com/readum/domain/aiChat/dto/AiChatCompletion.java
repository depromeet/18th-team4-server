package com.readum.domain.aiChat.dto;

import java.time.Duration;

/**
 * 비스트리밍 동기 호출 1회의 완성 결과.
 * 전량 버퍼 후 출력 검증하는 현행 정책에서는 리액티브 스트림 시절 같은 청크 단위 표현이 필요 없다.
 */
public record AiChatCompletion(
        String content,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        RateLimitSnapshot rateLimit
) {

    /**
     * 정상 응답에 부착되는 OpenAI rate limit 메타데이터 (429 응답 헤더와는 별개).
     * SSE 로 노출하지 않고 로깅/모니터링 목적으로만 실어 나른다.
     */
    public record RateLimitSnapshot(
            Long requestsLimit,
            Long requestsRemaining,
            Duration requestsReset,
            Long tokensLimit,
            Long tokensRemaining,
            Duration tokensReset
    ) {
    }
}
