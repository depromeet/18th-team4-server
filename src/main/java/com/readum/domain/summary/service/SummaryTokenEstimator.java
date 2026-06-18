package com.readum.domain.summary.service;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 요약 호출의 예상 토큰(입력 추정 + 예약 출력)을 계산한다 — batch 청크 토큰 예산 산정에 사용.
 * 입력은 대화 글자수 근사로 추정한다(한국어 혼용을 고려해 보수적으로 작은 글자/토큰 계수 사용).
 * 정밀 tokenizer(jtokkit 등) 도입은 확장점.
 */
@Component
public class SummaryTokenEstimator {

    // 한국어는 글자당 토큰이 더 든다 → 보수적으로(토큰을 더 크게) 잡기 위해 작은 값을 쓴다.
    private static final double CHARS_PER_TOKEN = 2.5;

    public int estimate(List<AiChatMessage> messages, int reservedOutputTokens) {
        int chars = messages.stream()
                .mapToInt(message -> message.getContent() == null ? 0 : message.getContent().length())
                .sum();
        int inputTokens = (int) Math.ceil(chars / CHARS_PER_TOKEN);
        return inputTokens + reservedOutputTokens;
    }
}
