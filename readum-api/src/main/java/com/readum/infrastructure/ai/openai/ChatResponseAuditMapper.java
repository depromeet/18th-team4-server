package com.readum.infrastructure.ai.openai;

import com.readum.infrastructure.ai.audit.AiPromptAuditEvent;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Spring AI {@link ChatResponse} 의 모델·토큰 메타데이터를 감사 이벤트로 옮기는 변환 헬퍼.
 *
 * <p>세 AI 어댑터(채팅/제목/감상문) 가 공유한다. 감사 이벤트({@link AiPromptAuditEvent}) 는 Spring AI 타입을
 * 모르는 순수 record 로 두고, 프레임워크 타입에 의존하는 추출 책임은 이 어댑터 계층 헬퍼에 모은다.
 */
final class ChatResponseAuditMapper {

    private ChatResponseAuditMapper() {
    }

    /**
     * 시작 이벤트에 호출 결과(모델·토큰·지연)를 덧채운다. 메타데이터/사용량이 비어 있으면 토큰은 0, 모델은 null 로 둔다.
     */
    static AiPromptAuditEvent applyResult(AiPromptAuditEvent base, ChatResponse response, long latencyMs) {
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        String model = metadata == null ? null : metadata.getModel();
        return base.completed(
                model,
                tokenOrZero(usage == null ? null : usage.getPromptTokens()),
                tokenOrZero(usage == null ? null : usage.getCompletionTokens()),
                tokenOrZero(usage == null ? null : usage.getTotalTokens()),
                latencyMs
        );
    }

    private static int tokenOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
