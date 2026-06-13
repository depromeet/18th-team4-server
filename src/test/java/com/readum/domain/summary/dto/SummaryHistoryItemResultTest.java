package com.readum.domain.summary.dto;

import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryHistoryItemResultTest {

    @Test
    void 본문이_100자_이하면_원문_그대로_노출한다() {
        String body = "가".repeat(50);

        SummaryHistoryItemResult result = SummaryHistoryItemResult.from(
                new SummaryHistoryProjection("책", body, LocalDateTime.now()));

        assertThat(result.content()).isEqualTo(body);
    }

    @Test
    void 본문이_정확히_100자면_말줄임표_없이_원문_그대로_노출한다() {
        String body = "가".repeat(100);

        SummaryHistoryItemResult result = SummaryHistoryItemResult.from(
                new SummaryHistoryProjection("책", body, LocalDateTime.now()));

        assertThat(result.content()).isEqualTo(body);
        assertThat(result.content()).doesNotEndWith("...");
    }

    @Test
    void 본문이_100자를_초과하면_앞_100자만_노출하고_말줄임표를_붙인다() {
        String body = "가".repeat(101);

        SummaryHistoryItemResult result = SummaryHistoryItemResult.from(
                new SummaryHistoryProjection("책", body, LocalDateTime.now()));

        assertThat(result.content()).isEqualTo("가".repeat(100) + "...");
        assertThat(result.content()).hasSize(103);
    }

    @Test
    void 본문이_null_이면_content_도_null_이다() {
        SummaryHistoryItemResult result = SummaryHistoryItemResult.from(
                new SummaryHistoryProjection("책", null, LocalDateTime.now()));

        assertThat(result.content()).isNull();
    }

    @Test
    void 책_제목과_생성일은_변형_없이_그대로_전달된다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 7, 9, 0, 0);

        SummaryHistoryItemResult result = SummaryHistoryItemResult.from(
                new SummaryHistoryProjection("데미안", "본문", createdAt));

        assertThat(result.bookTitle()).isEqualTo("데미안");
        assertThat(result.createdAt()).isEqualTo(createdAt);
    }
}
