package com.readum.domain.auth.out;

import com.readum.domain.auth.dto.ParsedToken;

import java.time.Duration;
import java.time.Instant;

public interface JwtTokenClient {

    String generateAccessToken(Long userId, String role, String jwtId);

    String generateRefreshToken(Long userId, String role, String jwtId);

    String generateRefreshToken(Long userId, String role, String jwtId, Instant issuedAt, Instant expiresAt);

    ParsedToken parse(String token);

    Duration accessTokenTtl();

    Duration refreshTokenTtl();

    Duration refreshGracePeriod();
}
