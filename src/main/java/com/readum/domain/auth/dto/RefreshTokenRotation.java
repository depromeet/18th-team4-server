package com.readum.domain.auth.dto;

import java.time.Duration;
import java.time.Instant;

public record RefreshTokenRotation(
        Long userId,
        String oldJwtId,
        String newJwtId,
        Instant newIssuedAt,
        Instant newExpiresAt,
        Duration gracePeriod
) {
}
