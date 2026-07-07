package com.readum.infrastructure.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void null_은_빈_문자열이_된다() {
        assertThat(SensitiveDataMasker.mask(null)).isEmpty();
    }

    @Test
    void bearer_토큰_값이_가려진다() {
        assertThat(SensitiveDataMasker.mask("Bearer abc.def.ghi")).startsWith("Bearer ***");
    }

    @Test
    void authorization_헤더_값이_가려진다() {
        assertThat(SensitiveDataMasker.mask("Authorization: Basic abc123"))
                .isEqualTo("Authorization: ***");
    }

    @Test
    void 쿼리스트링의_api_key_와_token_값이_가려진다() {
        String masked = SensitiveDataMasker.mask("GET /x?api_key=secret&token=hidden&page=1");

        assertThat(masked).contains("api_key=***");
        assertThat(masked).contains("token=***");
        assertThat(masked).doesNotContain("secret");
        assertThat(masked).doesNotContain("hidden");
        assertThat(masked).contains("page=1");
    }
}
