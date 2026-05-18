package com.readum.model.auth.entity;

import com.readum.support.TestOnly;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link RefreshToken} 을 만드는 명명 팩토리.
 * 토큰 수명 주기 상태(활성/폐기/만료/유예 중/유예 경과/자식)를 이름으로 드러낸다.
 * 같은 패키지의 package-private 전체필드 생성자를 컴파일-안전하게 호출한다.
 * createdAt 은 어떤 호출부에서도 단언하지 않으므로 내부 기본값(now)을 쓴다.
 */
@TestOnly
public final class RefreshTokenFixture {

    private RefreshTokenFixture() {
    }

    /**
     * 회전/유예/폐기 흔적이 없는 활성 토큰. {@code isActive(issuedAt~expiresAt 사이)} 가 true.
     */
    public static RefreshToken activeToken(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt
    ) {
        return new RefreshToken(
                id, userId, jwtId, null, issuedAt, expiresAt,
                null, null, null, LocalDateTime.now()
        );
    }

    /**
     * 명시적으로 폐기된 토큰 (revokedAt 설정). 재사용 감지 경로 검증용.
     */
    public static RefreshToken revokedToken(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt, Instant revokedAt
    ) {
        return new RefreshToken(
                id, userId, jwtId, null, issuedAt, expiresAt,
                null, null, revokedAt, LocalDateTime.now()
        );
    }

    /**
     * 만료된 토큰. expiresAt 이 이미 지난 시각으로 주어진다.
     */
    public static RefreshToken expiredToken(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt
    ) {
        return new RefreshToken(
                id, userId, jwtId, null, issuedAt, expiresAt,
                null, null, null, LocalDateTime.now()
        );
    }

    /**
     * 회전됐지만 아직 유예 기간 내인 토큰 (rotatedAt 설정, graceExpiresAt 이 미래).
     */
    public static RefreshToken tokenInGracePeriod(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt,
            Instant rotatedAt, Instant graceExpiresAt
    ) {
        return new RefreshToken(
                id, userId, jwtId, null, issuedAt, expiresAt,
                rotatedAt, graceExpiresAt, null, LocalDateTime.now()
        );
    }

    /**
     * 회전됐고 유예 기간도 이미 지난 토큰 (rotatedAt 설정, graceExpiresAt 이 과거).
     * 재사용 감지로 전체 폐기 경로 검증용.
     */
    public static RefreshToken postGraceToken(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt,
            Instant rotatedAt, Instant graceExpiresAt
    ) {
        return new RefreshToken(
                id, userId, jwtId, null, issuedAt, expiresAt,
                rotatedAt, graceExpiresAt, null, LocalDateTime.now()
        );
    }

    /**
     * 회전으로 발급된 자식 토큰 (parentJwtId 설정). 유예 기간 재서명 경로 검증용.
     */
    public static RefreshToken persistedChildToken(
            Long id, Long userId, String jwtId, String parentJwtId, Instant issuedAt, Instant expiresAt
    ) {
        return new RefreshToken(
                id, userId, jwtId, parentJwtId, issuedAt, expiresAt,
                null, null, null, LocalDateTime.now()
        );
    }
}
