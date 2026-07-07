package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link AiChatContextSummary} 를 만드는 명명 팩토리.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
 */
@TestOnly
public final class AiChatContextSummaryFixture {

    private AiChatContextSummaryFixture() {
    }

    /** 저장되어 id·version 이 부여된 요약. */
    public static AiChatContextSummary persisted(
            Long id, Long sessionId, String content, Long summarizedUntilMessageId, int version, int tokenCount
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new AiChatContextSummary(
                id, sessionId, content, summarizedUntilMessageId, version, tokenCount, now, now);
    }
}
