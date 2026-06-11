package com.readum.model.summary.entity;

import com.readum.support.TestOnly;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link Summary} 를 만드는 명명 팩토리.
 * 감상문 진행 상태(생성 중)를 이름으로 드러낸다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
 * createdAt/updatedAt 은 어떤 호출부에서도 단언하지 않으므로 내부 기본값(now)을 쓴다.
 */
@TestOnly
public final class SummaryFixture {

    private SummaryFixture() {
    }

    /**
     * 저장되어 id 가 부여된, IN_PROGRESS 상태의 감상문.
     * 본문(title/body/quote)은 아직 비어 있다.
     * summaryDate 는 오늘 날짜를 기본값으로 사용한다.
     */
    public static Summary persistedInProgressSummary(
            Long id, Long userBookId, Long aiChatSessionId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new Summary(
                id, userBookId, aiChatSessionId, LocalDate.now(),
                Summary.Status.IN_PROGRESS, 0, null, null, null, now, now
        );
    }
}
