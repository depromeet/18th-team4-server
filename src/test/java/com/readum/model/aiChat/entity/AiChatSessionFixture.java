package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 테스트에서 특정 id·상태의 {@link AiChatSession} 을 만들기 위한 조립기.
 * 운영 엔티티에 있던 {@code AiChatSession.of(...)} 를 대체한다.
 */
@TestOnly
public final class AiChatSessionFixture {

    private AiChatSessionFixture() {
    }

    public static AiChatSession of(
            Long id,
            Long userBookId,
            AiChatSession.Status status,
            int userMessageCount,
            int accumulatedTokens,
            String title,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        AiChatSession session = new AiChatSession();
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "userBookId", userBookId);
        ReflectionTestUtils.setField(session, "status", status);
        ReflectionTestUtils.setField(session, "userMessageCount", userMessageCount);
        ReflectionTestUtils.setField(session, "accumulatedTokens", accumulatedTokens);
        ReflectionTestUtils.setField(session, "title", title);
        ReflectionTestUtils.setField(session, "createdAt", createdAt);
        ReflectionTestUtils.setField(session, "updatedAt", updatedAt);
        return session;
    }
}
