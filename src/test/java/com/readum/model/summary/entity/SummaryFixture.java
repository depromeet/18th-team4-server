package com.readum.model.summary.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * 테스트에서 특정 id·상태의 {@link Summary} 를 만들기 위한 조립기.
 * 운영 엔티티에 있던 {@code Summary.of(...)} 를 대체한다.
 */
@TestOnly
public final class SummaryFixture {

    private SummaryFixture() {
    }

    public static Summary of(
            Long id,
            Long userBookId,
            Long aiChatSessionId,
            Summary.Status status,
            String quote,
            String title,
            String body,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        Summary summary = new Summary();
        ReflectionTestUtils.setField(summary, "id", id);
        ReflectionTestUtils.setField(summary, "userBookId", userBookId);
        ReflectionTestUtils.setField(summary, "aiChatSessionId", aiChatSessionId);
        ReflectionTestUtils.setField(summary, "status", status);
        ReflectionTestUtils.setField(summary, "quote", quote);
        ReflectionTestUtils.setField(summary, "title", title);
        ReflectionTestUtils.setField(summary, "body", body);
        ReflectionTestUtils.setField(summary, "createdAt", createdAt);
        ReflectionTestUtils.setField(summary, "updatedAt", updatedAt);
        return summary;
    }
}
