package com.readum.domain.summary.dto;

import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryHistoryItemResultTest {

    @Test
    void projection_의_모든_필드를_변형_없이_전달한다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 7, 9, 0, 0);

        SummaryHistoryItemResult result = SummaryHistoryItemResult.from(
                new SummaryHistoryProjection(42L, "데미안", "데미안을 읽고 나서", createdAt));

        assertThat(result.summaryId()).isEqualTo(42L);
        assertThat(result.bookTitle()).isEqualTo("데미안");
        assertThat(result.sessionTitle()).isEqualTo("데미안을 읽고 나서");
        assertThat(result.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void 세션_제목_생성이_실패해_title_이_null_이어도_그대로_통과시킨다() {
        SummaryHistoryItemResult result = SummaryHistoryItemResult.from(
                new SummaryHistoryProjection(1L, "책", null, LocalDateTime.now()));

        assertThat(result.sessionTitle()).isNull();
    }
}
