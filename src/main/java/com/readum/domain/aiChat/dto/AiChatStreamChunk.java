package com.readum.domain.aiChat.dto;

/**
 * [측정용 임시 — 조건 A] 스트리밍 생성의 청크 1건. 측정 후 처리 별도 결정, dev 머지 금지.
 *
 * 본문 조각(delta)과 실측 토큰 사용량을 함께 실어 나른다. 사용량은 스트림 마지막 청크에만 실린다
 * (OpenAI 의 {@code stream_options.include_usage}) — 그 청크의 delta 는 비어 있다.
 */
public record AiChatStreamChunk(
        String delta,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {

    /** 본문 조각만 담은 청크 (사용량 없음). */
    public static AiChatStreamChunk ofDelta(String delta) {
        return new AiChatStreamChunk(delta, null, null, null);
    }

    /** 실측 사용량이 실린 청크인지. 중간 청크의 0 짜리 usage 를 실측으로 오인하지 않도록 양수만 인정한다. */
    public boolean hasUsage() {
        return (totalTokens != null && totalTokens > 0)
                || (outputTokens != null && outputTokens > 0)
                || (inputTokens != null && inputTokens > 0);
    }
}
