package com.readum.infrastructure.ai.openai.ratelimit;

/** 게이트 계상용 문자 수 기반 토큰 추정 (한국어 보수 계수 2.5자/토큰). PR-2 에서 jtokkit 기반으로 교체 예정. */
public final class OpenAiTokenEstimate {

    private static final double CHARS_PER_TOKEN = 2.5;

    private OpenAiTokenEstimate() {
    }

    public static int fromChars(long charCount) {
        return (int) Math.ceil(charCount / CHARS_PER_TOKEN);
    }
}
