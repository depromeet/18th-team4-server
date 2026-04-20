package com.readum.model.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "refresh_token",
        indexes = {
                @Index(name = "idx_refresh_token_user_revoked", columnList = "user_id, revoked_at"),
                @Index(name = "idx_refresh_token_parent_jwt_id", columnList = "parent_jwt_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "jwt_id", nullable = false, length = 64, unique = true)
    private String jwtId;

    @Column(name = "parent_jwt_id", length = 64)
    private String parentJwtId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "grace_expires_at")
    private Instant graceExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static RefreshTokenEntity create(Long userId, String jwtId, Instant issuedAt, Instant expiresAt) {
        return new RefreshTokenEntity(
                null, userId, jwtId, null, issuedAt, expiresAt, null, null, null, LocalDateTime.now()
        );
    }

    public static RefreshTokenEntity createSuccessor(
            Long userId, String jwtId, String parentJwtId, Instant issuedAt, Instant expiresAt
    ) {
        return new RefreshTokenEntity(
                null, userId, jwtId, parentJwtId, issuedAt, expiresAt, null, null, null, LocalDateTime.now()
        );
    }

    public static RefreshTokenEntity of(
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
        return new RefreshTokenEntity(
                id, userId, jwtId, parentJwtId, issuedAt, expiresAt, rotatedAt, graceExpiresAt, revokedAt, createdAt
        );
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isActive(Instant now) {
        return rotatedAt == null && revokedAt == null && !isExpired(now);
    }

    public boolean isInGrace(Instant now) {
        return rotatedAt != null
                && revokedAt == null
                && graceExpiresAt != null
                && now.isBefore(graceExpiresAt);
    }

    public void markRotated(Instant now, Duration gracePeriod) {
        this.rotatedAt = now;
        this.graceExpiresAt = now.plus(gracePeriod);
    }
}
