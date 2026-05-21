package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link AiChatSession} 을 만드는 명명 팩토리.
 * 세션의 진행 상태(활성/종료)를 이름으로 드러낸다.
 * 운영 엔티티의 invariant 를 우회하지 않도록 엔티티 생성자는 PRIVATE 으로 두고,
 * 픽스처는 같은 패키지에서 접근 가능한 protected 무인자 생성자로 객체를 만든 뒤
 * {@link ReflectionTestUtils} 로 필드를 채운다 (테스트 전용).
 * createdAt/updatedAt 은 어떤 호출부에서도 단언하지 않으므로 내부 기본값(now)을 쓴다.
 */
@TestOnly
public final class AiChatSessionFixture {

    private AiChatSessionFixture() {
    }

    /**
     * 저장되어 id 가 부여된 활성(ACTIVE) 세션.
     */
    public static AiChatSession persistedActiveSession(
            Long id,
            Long userBookId,
            int userMessageCount,
            int accumulatedTokens,
            String title
    ) {
        return assemble(id, userBookId, AiChatSession.Status.ACTIVE,
                userMessageCount, accumulatedTokens, title);
    }

    /**
     * 저장되어 id 가 부여된 종료(CLOSED) 세션.
     */
    public static AiChatSession persistedClosedSession(
            Long id,
            Long userBookId,
            int userMessageCount,
            int accumulatedTokens,
            String title
    ) {
        return assemble(id, userBookId, AiChatSession.Status.CLOSED,
                userMessageCount, accumulatedTokens, title);
    }

    private static AiChatSession assemble(
            Long id,
            Long userBookId,
            AiChatSession.Status status,
            int userMessageCount,
            int accumulatedTokens,
            String title
    ) {
        LocalDateTime now = LocalDateTime.now();
        AiChatSession session = new AiChatSession();
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "userBookId", userBookId);
        ReflectionTestUtils.setField(session, "status", status);
        ReflectionTestUtils.setField(session, "userMessageCount", userMessageCount);
        ReflectionTestUtils.setField(session, "accumulatedTokens", accumulatedTokens);
        ReflectionTestUtils.setField(session, "title", title);
        ReflectionTestUtils.setField(session, "createdAt", now);
        ReflectionTestUtils.setField(session, "updatedAt", now);
        return session;
    }
}
