package com.readum.logprocessor.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessageNormalizerTest {

    private final ErrorMessageNormalizer normalizer = new ErrorMessageNormalizer();

    @Test
    void null_입력은_빈_문자열을_반환한다() {
        assertThat(normalizer.normalize(null)).isEqualTo("");
    }

    @Test
    void UUID를_placeholder로_치환한다() {
        assertThat(normalizer.normalize("sessionId=018f9a2e-7c1d-4b2a-9f3e-1a2b3c4d5e6f failed"))
                .isEqualTo("sessionId={uuid} failed");
    }

    @Test
    void 숫자를_placeholder로_치환한다() {
        assertThat(normalizer.normalize("userId=12345 not found"))
                .isEqualTo("userId={number} not found");
    }

    @Test
    void 같은_원인_다른_식별자는_같은_정규화_결과를_낸다() {
        String a = normalizer.normalize("summary for sessionId=018f9a2e-7c1d-4b2a-9f3e-1a2b3c4d5e6f userId=12345");
        String b = normalizer.normalize("summary for sessionId=02b3d1f0-9a00-4c11-8d22-3e44f55a66b7 userId=67890");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 식별자가_없는_메시지는_그대로_둔다() {
        assertThat(normalizer.normalize("Lock wait timeout exceeded"))
                .isEqualTo("Lock wait timeout exceeded");
    }
}
