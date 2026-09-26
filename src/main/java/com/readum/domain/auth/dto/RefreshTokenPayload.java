package com.readum.domain.auth.dto;

import java.time.Instant;

public record RefreshTokenPayload(
        Long userId,
        String role,
        String jwtId,
        Instant issuedAt,
        Instant expiresAt
) {
}
