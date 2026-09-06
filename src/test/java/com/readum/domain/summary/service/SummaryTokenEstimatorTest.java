package com.readum.domain.summary.service;

import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryTokenEstimatorTest {

    // 결정적 test double(합산 로직만 검증). 기존 단언값 유지를 위해 문자÷2.5 로 센다.
    private final TokenCounter tokenCounter = text ->
            (text == null || text.isEmpty()) ? 0 : (int) Math.ceil(text.length() / 2.5);

    private final SummaryTokenEstimator estimator = new SummaryTokenEstimator(tokenCounter);

    @Test
    void 빈_대화면_예약_출력_토큰만_나온다() {
        int estimated = estimator.estimate(List.of(), 1024);

        assertThat(estimated).isEqualTo(1024);
    }

    @Test
    void 대화_글자수에_비례해_입력_토큰을_더한다() {
        // 총 50자 → ceil(50 / 2.5) = 20 입력 토큰, + 예약 출력 1000 = 1020
        List<AiChatMessage> messages = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, 1L, "가".repeat(25)),
                AiChatMessageFixture.persistedAssistantMessage(2L, 1L, "나".repeat(25))
        );

        int estimated = estimator.estimate(messages, 1000);

        assertThat(estimated).isEqualTo(1020);
    }
}
