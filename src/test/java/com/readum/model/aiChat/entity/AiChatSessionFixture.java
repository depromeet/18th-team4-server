package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link AiChatSession} 을 만드는 명명 팩토리.
 * 세션의 진행 상태(활성/잠김)를 이름으로 드러낸다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
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
        return persisted(id, userBookId, AiChatSession.Status.ACTIVE,
                userMessageCount, accumulatedTokens, title);
    }

    /**
     * 저장되어 id 가 부여된, 감상문이 완성되어 종료(LOCKED)된 세션.
     */
    public static AiChatSession persistedSummarizedSession(
            Long id,
            Long userBookId,
            int userMessageCount,
            int accumulatedTokens,
            String title
    ) {
        return persisted(id, userBookId, AiChatSession.Status.LOCKED,
                userMessageCount, accumulatedTokens, title);
    }

    private static AiChatSession persisted(
            Long id,
            Long userBookId,
            AiChatSession.Status status,
            int userMessageCount,
            int accumulatedTokens,
            String title
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new AiChatSession(
                id, userBookId, status, userMessageCount, accumulatedTokens, title, now, now
        );
    }
}
