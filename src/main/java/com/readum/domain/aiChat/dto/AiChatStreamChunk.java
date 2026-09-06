package com.readum.domain.aiChat.dto;

/**
 * 스트리밍 생성의 청크 1건. 본문 조각(delta), 종료 사유(finishReason), 실측 토큰 사용량을 함께 실어 나른다.
 *
 * <p>한 청크가 셋을 모두 담지는 않는다 — 스트림에는 다음 모양이 섞여 온다.
 * <ul>
 *   <li>본문 조각만 있는 청크 (대부분)</li>
 *   <li>본문 없이 종료 사유만 있는 청크 — 마지막 본문 조각 뒤에 온다</li>
 *   <li>본문 없이 사용량만 있는 청크 — OpenAI {@code stream_options.include_usage} 로 스트림 맨 뒤에 온다</li>
 * </ul>
 * 본문이 비었다는 것만으로 오류로 보지 않는다. 뒤의 두 모양은 정상 청크다.
 *
 * <p>사용량은 <b>청크별로 더하지 않는다</b>. 공급자가 스트림 마지막에 한 번 보내는 전체 사용량이므로,
 * 소비자는 마지막으로 받은 유효 사용량({@link #hasValidUsage()} 인 청크의 값)을 그 턴의 실측으로 쓴다.
 */
public record AiChatStreamChunk(
        String delta,
        String finishReason,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {

    /** 본문 조각만 담은 청크 (종료 사유·사용량 없음). */
    public static AiChatStreamChunk ofDelta(String delta) {
        return new AiChatStreamChunk(delta, null, null, null, null);
    }

    /** 본문 없이 종료 사유만 담은 메타데이터 전용 청크. */
    public static AiChatStreamChunk ofFinishReason(String finishReason) {
        return new AiChatStreamChunk("", finishReason, null, null, null);
    }

    /** 본문 없이 실측 사용량만 담은 사용량 전용 청크 — 스트림의 마지막 청크 모양. */
    public static AiChatStreamChunk ofUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        return new AiChatStreamChunk("", null, inputTokens, outputTokens, totalTokens);
    }

    /** 이 청크에 클라이언트로 내보낼 본문 조각이 있는지. */
    public boolean hasDelta() {
        return delta != null && !delta.isEmpty();
    }

    /** 본문 없이 메타데이터(종료 사유)나 사용량만 실린 청크인지 — 정상 모양이며 오류가 아니다. */
    public boolean isMetadataOnly() {
        return !hasDelta() && (hasFinishReason() || hasValidUsage());
    }

    /** 공급자가 알려준 종료 사유가 실린 청크인지. 값은 원본 그대로 보존한다 (예: {@code STOP}, {@code LENGTH}). */
    public boolean hasFinishReason() {
        return finishReason != null && !finishReason.isBlank();
    }

    /**
     * 실측으로 인정할 사용량이 실린 청크인지. 판정 기준은 <b>세 값이 모두 있고, 음수가 없고, 전체가 0 이 아님</b>이다.
     *
     * <p>세 값 중 하나라도 비면 정산에 그대로 쓸 수 없어 인정하지 않는다. 음수는 있을 수 없는 값이라 인정하지 않는다.
     * 전부 0 인 값을 걸러내는 이유는 따로 있다 — Spring AI 는 사용량이 없는 중간 청크에도 0 짜리 사용량을 채워 보내므로,
     * 0 을 인정하면 중간 청크를 실측 도착으로 오인한다.
     */
    public boolean hasValidUsage() {
        if (inputTokens == null || outputTokens == null || totalTokens == null) {
            return false;
        }
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            return false;
        }
        return totalTokens > 0;
    }
}
