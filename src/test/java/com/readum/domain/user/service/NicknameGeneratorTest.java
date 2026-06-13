package com.readum.domain.user.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameGeneratorTest {

    // 닉네임 제약: 영어 대/소문자 + 한글 + 숫자, 최대 10자.
    private static final String NICKNAME_PATTERN = "^[A-Za-z0-9가-힣]{1,10}$";

    private final NicknameGenerator nicknameGenerator = new NicknameGenerator();

    @Test
    void 모든_후보_닉네임은_제약_정규식을_만족한다() {
        assertThat(NicknameGenerator.NICKNAMES)
                .isNotEmpty()
                .allSatisfy(nickname -> assertThat(nickname).matches(NICKNAME_PATTERN));
    }

    @Test
    void 생성된_닉네임은_후보_목록에_포함된다() {
        for (int i = 0; i < 50; i++) {
            assertThat(NicknameGenerator.NICKNAMES).contains(nicknameGenerator.generate());
        }
    }
}
