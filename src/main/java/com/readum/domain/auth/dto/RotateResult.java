package com.readum.domain.auth.dto;

import java.time.Instant;

public record RotateResult(
        RotateOutcome outcome,
        String graceChildJwtId,
        Instant graceChildIssuedAt,
        Instant graceChildExpiresAt
) {

    public static RotateResult rotated() {
        return new RotateResult(RotateOutcome.ROTATED, null, null, null);
    }

    public static RotateResult graceHit(String childJwtId, Instant issuedAt, Instant expiresAt) {
        return new RotateResult(RotateOutcome.GRACE_HIT, childJwtId, issuedAt, expiresAt);
    }

    public static RotateResult notFound() {
        return new RotateResult(RotateOutcome.NOT_FOUND, null, null, null);
    }

    public static RotateResult expired() {
        return new RotateResult(RotateOutcome.EXPIRED, null, null, null);
    }

    public static RotateResult reuseDetected() {
        return new RotateResult(RotateOutcome.REUSE_DETECTED, null, null, null);
    }
}
