package com.readum.logprocessor.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintGeneratorTest {

    private final FingerprintGenerator generator = new FingerprintGenerator(new ErrorMessageNormalizer());

    @Test
    void 같은_입력은_같은_fingerprint를_낸다() {
        FingerprintInput input = new FingerprintInput(
                "java.lang.NullPointerException", "boom", "com.readum.A.run(A.java:1)", "GET", "/api/v1/books/{id}");

        assertThat(generator.generate(input)).isEqualTo(generator.generate(input));
    }

    @Test
    void 식별자만_다른_메시지는_같은_fingerprint를_낸다() {
        FingerprintInput a = new FingerprintInput(
                "LockTimeout", "summary userId=12345", "com.readum.A.run(A.java:1)", "POST", "/api/v1/summaries");
        FingerprintInput b = new FingerprintInput(
                "LockTimeout", "summary userId=67890", "com.readum.A.run(A.java:1)", "POST", "/api/v1/summaries");

        assertThat(generator.generate(a)).isEqualTo(generator.generate(b));
    }

    @Test
    void 예외_클래스가_다르면_다른_fingerprint를_낸다() {
        FingerprintInput a = new FingerprintInput("ExceptionA", "boom", "com.readum.A.run(A.java:1)", "GET", "/x");
        FingerprintInput b = new FingerprintInput("ExceptionB", "boom", "com.readum.A.run(A.java:1)", "GET", "/x");

        assertThat(generator.generate(a)).isNotEqualTo(generator.generate(b));
    }

    @Test
    void 결과는_64자리_hex다() {
        FingerprintInput input = new FingerprintInput("E", "m", "f", "GET", "/x");

        assertThat(generator.generate(input)).matches("[0-9a-f]{64}");
    }

    @Test
    void null_필드가_있어도_예외없이_생성한다() {
        FingerprintInput input = new FingerprintInput(null, null, null, null, null);

        assertThat(generator.generate(input)).matches("[0-9a-f]{64}");
    }
}
