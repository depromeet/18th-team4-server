package com.readum.model.auth.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 테스트에서 특정 id·상태의 {@link RefreshToken} 을 만들기 위한 조립기.
 * 운영 엔티티에 있던 {@code RefreshToken.of(...)} 를 대체한다.
 */
@TestOnly
public final class RefreshTokenFixture {

    private RefreshTokenFixture() {
    }

    public static RefreshToken of(
            Long id,
            Long userId,
            String jwtId,
            String parentJwtId,
            Instant issuedAt,
            Instant expiresAt,
            Instant rotatedAt,
            Instant graceExpiresAt,
            Instant revokedAt,
            LocalDateTime createdAt
    ) {
        RefreshToken refreshToken = new RefreshToken();
        ReflectionTestUtils.setField(refreshToken, "id", id);
        ReflectionTestUtils.setField(refreshToken, "userId", userId);
        ReflectionTestUtils.setField(refreshToken, "jwtId", jwtId);
        ReflectionTestUtils.setField(refreshToken, "parentJwtId", parentJwtId);
        ReflectionTestUtils.setField(refreshToken, "issuedAt", issuedAt);
        ReflectionTestUtils.setField(refreshToken, "expiresAt", expiresAt);
        ReflectionTestUtils.setField(refreshToken, "rotatedAt", rotatedAt);
        ReflectionTestUtils.setField(refreshToken, "graceExpiresAt", graceExpiresAt);
        ReflectionTestUtils.setField(refreshToken, "revokedAt", revokedAt);
        ReflectionTestUtils.setField(refreshToken, "createdAt", createdAt);
        return refreshToken;
    }
}
