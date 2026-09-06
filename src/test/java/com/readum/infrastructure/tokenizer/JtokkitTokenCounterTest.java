package com.readum.infrastructure.tokenizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JtokkitTokenCounterTest {

    private final JtokkitTokenCounter counter = new JtokkitTokenCounter();

    @Test
    void null_과_빈_문자열은_0() {
        assertThat(counter.count(null)).isZero();
        assertThat(counter.count("")).isZero();
    }

    @Test
    void 텍스트는_양수_토큰() {
        assertThat(counter.count("안녕하세요, 오늘 읽은 책 이야기를 해볼까요?")).isPositive();
    }

    @Test
    void 더_긴_텍스트는_토큰이_더_많거나_같다() {
        int shorter = counter.count("독서 감상");
        int longer = counter.count("독서 감상을 아주 길게 풀어서 여러 문장으로 적어 봅니다.");
        assertThat(longer).isGreaterThanOrEqualTo(shorter);
    }

    @Test
    void 영어_짧은_문장은_알려진_소수_토큰() {
        // o200k_base 로 "hello world" 는 2 토큰. tiktoken 기준 고정값.
        assertThat(counter.count("hello world")).isEqualTo(2);
    }
}
