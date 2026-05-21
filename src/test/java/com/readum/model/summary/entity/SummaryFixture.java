package com.readum.model.summary.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link Summary} 를 만드는 명명 팩토리.
 * 감상문 진행 상태(생성 중)를 이름으로 드러낸다.
 * 운영 엔티티의 invariant 를 우회하지 않도록 엔티티 생성자는 PRIVATE 으로 두고,
 * 픽스처는 같은 패키지에서 접근 가능한 protected 무인자 생성자로 객체를 만든 뒤
 * {@link ReflectionTestUtils} 로 필드를 채운다 (테스트 전용).
 * createdAt/updatedAt 은 어떤 호출부에서도 단언하지 않으므로 내부 기본값(now)을 쓴다.
 */
@TestOnly
public final class SummaryFixture {

    private SummaryFixture() {
    }

    /**
     * 저장되어 id 가 부여된, IN_PROGRESS 상태의 감상문.
     * 본문(title/body/quote)은 아직 비어 있다.
     */
    public static Summary persistedInProgressSummary(
            Long id, Long userBookId, Long aiChatSessionId
    ) {
        return assemble(
                id, userBookId, aiChatSessionId, Summary.Status.IN_PROGRESS,
                null, null, null
        );
    }

    private static Summary assemble(
            Long id,
            Long userBookId,
            Long aiChatSessionId,
            Summary.Status status,
            String quote,
            String title,
            String body
    ) {
        LocalDateTime now = LocalDateTime.now();
        Summary summary = new Summary();
        ReflectionTestUtils.setField(summary, "id", id);
        ReflectionTestUtils.setField(summary, "userBookId", userBookId);
        ReflectionTestUtils.setField(summary, "aiChatSessionId", aiChatSessionId);
        ReflectionTestUtils.setField(summary, "status", status);
        ReflectionTestUtils.setField(summary, "quote", quote);
        ReflectionTestUtils.setField(summary, "title", title);
        ReflectionTestUtils.setField(summary, "body", body);
        ReflectionTestUtils.setField(summary, "createdAt", now);
        ReflectionTestUtils.setField(summary, "updatedAt", now);
        return summary;
    }
}
