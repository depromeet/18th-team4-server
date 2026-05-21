package com.readum.model.auth.entity;

import com.readum.support.TestOnly;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link RefreshToken} 을 만드는 명명 팩토리.
 * 토큰 수명 주기 상태(활성/폐기/만료/유예 중/유예 경과/자식)를 이름으로 드러낸다.
 * 운영 엔티티의 invariant 를 우회하지 않도록 엔티티 생성자는 PRIVATE 으로 두고,
 * 픽스처는 같은 패키지에서 접근 가능한 protected 무인자 생성자로 객체를 만든 뒤
 * {@link ReflectionTestUtils} 로 필드를 채운다 (테스트 전용).
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
        return assemble(id, userId, jwtId, null, issuedAt, expiresAt, null, null, null);
    }

    /**
     * 명시적으로 폐기된 토큰 (revokedAt 설정). 재사용 감지 경로 검증용.
     */
    public static RefreshToken revokedToken(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt, Instant revokedAt
    ) {
        return assemble(id, userId, jwtId, null, issuedAt, expiresAt, null, null, revokedAt);
    }

    /**
     * 만료된 토큰. expiresAt 이 이미 지난 시각으로 주어진다.
     */
    public static RefreshToken expiredToken(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt
    ) {
        return assemble(id, userId, jwtId, null, issuedAt, expiresAt, null, null, null);
    }

    /**
     * 회전됐지만 아직 유예 기간 내인 토큰 (rotatedAt 설정, graceExpiresAt 이 미래).
     */
    public static RefreshToken tokenInGracePeriod(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt,
            Instant rotatedAt, Instant graceExpiresAt
    ) {
        return assemble(id, userId, jwtId, null, issuedAt, expiresAt, rotatedAt, graceExpiresAt, null);
    }

    /**
     * 회전됐고 유예 기간도 이미 지난 토큰 (rotatedAt 설정, graceExpiresAt 이 과거).
     * 재사용 감지로 전체 폐기 경로 검증용.
     */
    public static RefreshToken postGraceToken(
            Long id, Long userId, String jwtId, Instant issuedAt, Instant expiresAt,
            Instant rotatedAt, Instant graceExpiresAt
    ) {
        return assemble(id, userId, jwtId, null, issuedAt, expiresAt, rotatedAt, graceExpiresAt, null);
    }

    /**
     * 회전으로 발급된 자식 토큰 (parentJwtId 설정). 유예 기간 재서명 경로 검증용.
     */
    public static RefreshToken persistedChildToken(
            Long id, Long userId, String jwtId, String parentJwtId, Instant issuedAt, Instant expiresAt
    ) {
        return assemble(id, userId, jwtId, parentJwtId, issuedAt, expiresAt, null, null, null);
    }

    /**
     * 모든 필드를 받아 {@link ReflectionTestUtils} 로 직접 채우는 조립 헬퍼.
     * 명명 팩토리만 외부에 노출하고, 필드 주입 메커니즘은 여기 한 곳에 묶는다.
     */
    private static RefreshToken assemble(
            Long id, Long userId, String jwtId, String parentJwtId,
            Instant issuedAt, Instant expiresAt,
            Instant rotatedAt, Instant graceExpiresAt, Instant revokedAt
    ) {
        RefreshToken token = new RefreshToken();
        ReflectionTestUtils.setField(token, "id", id);
        ReflectionTestUtils.setField(token, "userId", userId);
        ReflectionTestUtils.setField(token, "jwtId", jwtId);
        ReflectionTestUtils.setField(token, "parentJwtId", parentJwtId);
        ReflectionTestUtils.setField(token, "issuedAt", issuedAt);
        ReflectionTestUtils.setField(token, "expiresAt", expiresAt);
        ReflectionTestUtils.setField(token, "rotatedAt", rotatedAt);
        ReflectionTestUtils.setField(token, "graceExpiresAt", graceExpiresAt);
        ReflectionTestUtils.setField(token, "revokedAt", revokedAt);
        ReflectionTestUtils.setField(token, "createdAt", LocalDateTime.now());
        return token;
    }
}
