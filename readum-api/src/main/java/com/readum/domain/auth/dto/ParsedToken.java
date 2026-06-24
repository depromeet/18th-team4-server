package com.readum.domain.auth.dto;

import java.time.Instant;

public record ParsedToken(
        Long userId,
        String role,
        String jwtId,
        Instant expiresAt,
        TokenType type
) {

    public enum TokenType {
        ACCESS, REFRESH
    }
}
