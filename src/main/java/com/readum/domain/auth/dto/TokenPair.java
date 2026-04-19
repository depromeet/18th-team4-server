package com.readum.domain.auth.dto;

import java.time.Duration;

public record TokenPair(
        String accessToken,
        String refreshToken,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
}
