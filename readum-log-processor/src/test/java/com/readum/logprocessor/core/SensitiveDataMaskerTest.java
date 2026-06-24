package com.readum.logprocessor.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void null_입력은_빈_문자열을_반환한다() {
        assertThat(masker.mask(null)).isEqualTo("");
    }

    @Test
    void Bearer_토큰을_가린다() {
        assertThat(masker.mask("Bearer abc.def.ghi"))
                .doesNotContain("abc.def.ghi")
                .contains("Bearer ***");
    }

    @Test
    void 비_Bearer_Authorization_헤더도_가린다() {
        assertThat(masker.mask("Authorization: Basic dXNlcjpwYXNz"))
                .doesNotContain("dXNlcjpwYXNz")
                .contains("***");
    }

    @Test
    void api_key_쿼리_파라미터를_가린다() {
        assertThat(masker.mask("GET /search?api_key=SECRET123&q=book"))
                .doesNotContain("SECRET123")
                .contains("api_key=***");
    }

    @Test
    void token_쿼리_파라미터를_가린다() {
        assertThat(masker.mask("refresh token=eyJ0eXAiOiJKV1Q"))
                .doesNotContain("eyJ0eXAiOiJKV1Q")
                .contains("token=***");
    }

    @Test
    void 이메일을_가린다() {
        assertThat(masker.mask("user digi1k2001@gmail.com 로그인 실패"))
                .doesNotContain("digi1k2001@gmail.com")
                .contains("{email}");
    }

    @Test
    void 일반_텍스트는_그대로_둔다() {
        assertThat(masker.mask("NullPointerException at SummaryJobWorker.run"))
                .isEqualTo("NullPointerException at SummaryJobWorker.run");
    }
}
