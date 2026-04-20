package com.readum.domain.auth.out;

import com.readum.domain.auth.dto.RefreshTokenRotation;
import com.readum.domain.auth.dto.RotateResult;

import java.time.Instant;

public interface RefreshTokenStore {

    void save(Long userId, String jwtId, Instant issuedAt, Instant expiresAt);

    RotateResult rotate(RefreshTokenRotation rotation);

    void revokeAll(Long userId);
}
