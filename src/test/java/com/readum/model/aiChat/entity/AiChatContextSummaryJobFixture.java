package com.readum.model.aiChat.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link AiChatContextSummaryJob} 를 만드는 명명 팩토리.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
 */
@TestOnly
public final class AiChatContextSummaryJobFixture {

    private AiChatContextSummaryJobFixture() {
    }

    /** 즉시 처리 가능한, 저장되어 id 가 부여된 PENDING 작업. */
    public static AiChatContextSummaryJob persistedPending(Long id, Long sessionId) {
        return persistedPendingDueAt(id, sessionId, LocalDateTime.now());
    }

    /** nextAttemptAt(처리 가능 시각)을 지정한 PENDING 작업 — findClaimable 시점·정렬 검증용. */
    public static AiChatContextSummaryJob persistedPendingDueAt(Long id, Long sessionId, LocalDateTime nextAttemptAt) {
        LocalDateTime now = LocalDateTime.now();
        return new AiChatContextSummaryJob(
                id, sessionId, sessionId, AiChatContextSummaryJob.Status.PENDING,
                null, null, 0, nextAttemptAt, null, null, now, now);
    }

    /** owner 가 점유(PROCESSING) 중인, lease 가 lockedUntil 까지 유효한 작업. */
    public static AiChatContextSummaryJob persistedProcessing(
            Long id, Long sessionId, String owner, LocalDateTime lockedUntil, int attemptCount
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new AiChatContextSummaryJob(
                id, sessionId, sessionId, AiChatContextSummaryJob.Status.PROCESSING,
                owner, lockedUntil, attemptCount, now, null, null, now, now);
    }
}
