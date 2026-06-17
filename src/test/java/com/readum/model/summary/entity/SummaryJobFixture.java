package com.readum.model.summary.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link SummaryJob} 을 만드는 명명 팩토리. 같은 패키지의 package-private 전체필드 생성자 호출.
 */
@TestOnly
public final class SummaryJobFixture {

    private SummaryJobFixture() {
    }

    /** 저장되어 처리 대기(PENDING)인 작업. nextAttemptAt 으로 처리 가능 시점을 제어한다. */
    public static SummaryJob persistedPending(Long id, Long sessionId, LocalDateTime nextAttemptAt) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.Status.PENDING,
                null, null, 0, nextAttemptAt, null, null, now, now
        );
    }

    /** 저장되어 점유(PROCESSING)된 작업. lockedUntil 로 lease 만료 여부를 제어한다(회수기 테스트용). */
    public static SummaryJob persistedProcessing(
            Long id, Long sessionId, String owner, LocalDateTime lockedUntil
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new SummaryJob(
                id, sessionId, sessionId, SummaryJob.Status.PROCESSING,
                owner, lockedUntil, 0, now, null, null, now, now
        );
    }
}
