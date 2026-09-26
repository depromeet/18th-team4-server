package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요약 구간 값 객체의 팩토리 계약 테스트. of() 는 빈 묶음을 none() 으로 흡수하고,
 * 비지 않은 묶음은 마지막 원소 id 를 요약 반영 지점으로 삼는다.
 */
class SummaryRangeTest {

    @Test
    void of_는_빈_묶음을_none_으로_흡수한다() {
        SummaryRange range = SummaryRange.of(List.of());

        assertThat(range.isEmpty()).isTrue();
        assertThat(range.lastSummarizedMessageId()).isNull();
    }

    @Test
    void of_는_마지막_원소_id_를_요약_반영_지점으로_삼는다() {
        List<AiChatMessage> messages = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, 3L, "q"),
                AiChatMessageFixture.persistedAssistantMessage(2L, 3L, "a"));

        SummaryRange range = SummaryRange.of(messages);

        assertThat(range.isEmpty()).isFalse();
        assertThat(range.lastSummarizedMessageId()).isEqualTo(2L);
    }
}
