package com.readum.incident.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 같은 원인의 에러를 한 incident 로 묶기 위한 식별자(fingerprint)를 만든다.
 * 가변 메시지는 ErrorMessageNormalizer 로 정규화한 뒤 다른 안정 요소와 합쳐 SHA-256 한다.
 */
public final class FingerprintGenerator {

    private final ErrorMessageNormalizer normalizer;

    public FingerprintGenerator(ErrorMessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public String generate(FingerprintInput input) {
        String basis = String.join("|",
                safe(input.exceptionClass()),
                normalizer.normalize(input.message()),
                safe(input.topApplicationFrame()),
                safe(input.requestMethod()),
                safe(input.requestUri()));
        return sha256Hex(basis);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 보장하는 알고리즘 — 여기 오면 환경이 깨진 것이므로 즉시 멈춘다.
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
