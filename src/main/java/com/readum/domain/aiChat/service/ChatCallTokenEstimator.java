package com.readum.domain.aiChat.service;

import org.springframework.stereotype.Component;

/**
 * 채팅 호출의 사용자 몫 토큰 추정 — 토큰 예산 선불 예약과 스트림 사망 시 대체 기록에 쓴다.
 * 사용자 예산은 <b>사용자가 보낸 메시지 입력 + 받은 응답 출력</b>만 계상한다. 시스템 프롬프트·재전송된
 * 이전 대화·요약 등 서비스 오버헤드는 우리가 얹는 것이라 사용자에게 청구하지 않는다(공정성 한도).
 * 문자 수 ÷ 2.5 (한국어 보수 추정, SummaryTokenEstimator 와 같은 계수). PR-2 에서 jtokkit 기반
 * 정확 계산으로 교체 예정이다.
 */
@Component
public class ChatCallTokenEstimator {

    private static final double CHARS_PER_TOKEN = 2.5;

    /** 사용자가 보낸 메시지 한 건의 입력 토큰 추정 (오버헤드 미포함). */
    public int estimateMessageInputTokens(String message) {
        return estimateTokensFromChars(message.length());
    }

    public int estimateTokensFromChars(int charCount) {
        return (int) Math.ceil(charCount / CHARS_PER_TOKEN);
    }
}
