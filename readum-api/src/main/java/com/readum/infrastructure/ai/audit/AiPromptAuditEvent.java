package com.readum.infrastructure.ai.audit;

/**
 * AI 호출 한 건에 대해 감사 로그로 남길 메타데이터.
 *
 * <p>prompt/completion 원문은 담지 않는다. 식별이 필요한 값은 해시(예: conversationIdHash, promptHash)로만 보관한다.
 */
public record AiPromptAuditEvent(
        String conversationIdHash,
        String promptTemplateId,
        String promptTemplateVersion,
        String model,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        long latencyMs,
        String status,
        String errorClass,
        String promptHash
) {
    /**
     * 호출 직전에 알 수 있는 값(어떤 대화·어떤 프롬프트인지)만 채운 시작 이벤트.
     * 모델/토큰/지연/상태는 호출이 끝난 뒤 {@link #completed} 로 덧채운다.
     */
    public static AiPromptAuditEvent started(
            String conversationIdHash,
            String promptTemplateId,
            String promptTemplateVersion,
            String promptHash
    ) {
        return new AiPromptAuditEvent(
                conversationIdHash,
                promptTemplateId,
                promptTemplateVersion,
                null,
                0,
                0,
                0,
                0L,
                null,
                null,
                promptHash
        );
    }

    /**
     * 호출 결과(모델·토큰·지연)를 덧채운 이벤트. 시작 이벤트에서 잡아둔 식별 정보는 그대로 유지한다.
     * 성공/실패 구분은 이후 {@link #withStatus} 에서 결정한다.
     */
    public AiPromptAuditEvent completed(
            String model,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            long latencyMs
    ) {
        return new AiPromptAuditEvent(
                conversationIdHash,
                promptTemplateId,
                promptTemplateVersion,
                model,
                inputTokens,
                outputTokens,
                totalTokens,
                latencyMs,
                status,
                errorClass,
                promptHash
        );
    }

    public AiPromptAuditEvent withStatus(String status, String errorClass) {
        return new AiPromptAuditEvent(
                conversationIdHash,
                promptTemplateId,
                promptTemplateVersion,
                model,
                inputTokens,
                outputTokens,
                totalTokens,
                latencyMs,
                status,
                errorClass,
                promptHash
        );
    }
}
