package com.readum.domain.aiChat.out;

/**
 * 텍스트의 토큰 수를 gpt-4o-mini 기준(o200k_base)으로 로컬 계산한다. 문자÷2.5 추정을 대체.
 * 사용자 예산 추정·감상문 추정·게이트 계상·이력 조립이 이 한 구현을 공유한다.
 */
public interface TokenCounter {

    /** null·빈 문자열은 0. */
    int count(String text);
}
