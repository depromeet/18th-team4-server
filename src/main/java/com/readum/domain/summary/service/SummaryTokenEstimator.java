package com.readum.domain.summary.service;

import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 요약 호출의 예상 토큰(입력 계산 + 출력 추정)을 계산한다 — 세션 과대(fail-fast) 판정에 사용.
 * 입력은 jtokkit(o200k_base) 로 각 메시지 내용의 토큰을 합산한다.
 */
@Component
@RequiredArgsConstructor
public class SummaryTokenEstimator {

    private final TokenCounter tokenCounter;

    public int estimate(List<AiChatMessage> messages, int estimatedOutputTokens) {
        int inputTokens = messages.stream()
                .mapToInt(message -> tokenCounter.count(message.getContent()))
                .sum();
        return inputTokens + estimatedOutputTokens;
    }
}
