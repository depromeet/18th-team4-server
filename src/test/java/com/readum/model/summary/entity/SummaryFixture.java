package com.readum.model.summary.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 저장되어 id 가 부여된 {@link Summary} 를 만드는 명명 팩토리.
 * 감상문은 성공 기록만 남으므로(write-once, 실패 행 없음) 한 종류만 노출한다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
 * createdAt/updatedAt 은 어떤 호출부에서도 단언하지 않으므로 내부 기본값(now)을 쓴다.
 */
@TestOnly
public final class SummaryFixture {

    private SummaryFixture() {
    }

    /** 저장되어 id 가 부여된 감상문. */
    public static Summary persistedSummary(
            Long id, Long userBookId, Long aiChatSessionId, String title, String body
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(id, userBookId, aiChatSessionId, null, title, body, now, now);
    }

    /**
     * createdAt 을 지정해 만드는 감상문. 생성일 기준 조회(월별 달력)처럼 createdAt 을 제어해야 할 때 쓴다.
     * id 에 null 을 넘기면 저장 시 IDENTITY 가 생성한다 (DAO 테스트용).
     */
    public static Summary persistedSummaryCreatedAt(
            Long id, Long userBookId, Long aiChatSessionId, String title, String body, LocalDateTime createdAt
    ) {
        return new Summary(id, userBookId, aiChatSessionId, null, title, body, createdAt, createdAt);
    }
}
