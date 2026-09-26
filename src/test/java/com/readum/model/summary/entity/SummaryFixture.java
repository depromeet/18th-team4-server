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
}
