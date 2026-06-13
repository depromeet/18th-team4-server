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
