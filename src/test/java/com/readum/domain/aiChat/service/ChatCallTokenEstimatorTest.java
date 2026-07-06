package com.readum.domain.aiChat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCallTokenEstimatorTest {

    private final ChatCallTokenEstimator estimator = new ChatCallTokenEstimator();

    @Test
    void 사용자_메시지_문자수를_계수로_나눠_올림한다() {
        // 사용자 예산은 사용자가 보낸 메시지만 계상한다(이력·시스템 프롬프트 미포함).
        // "1234567" 7자 / 2.5 = 2.8 → 3
        assertThat(estimator.estimateMessageInputTokens("1234567")).isEqualTo(3);
    }

    @Test
    void 문자_수_기반_추정은_올림한다() {
        assertThat(estimator.estimateTokensFromChars(6)).isEqualTo(3); // 6 / 2.5 = 2.4 → 3
    }
}
