package com.readum.model.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "refresh_token",
        indexes = {
                @Index(name = "idx_refresh_token_user_revoked", columnList = "user_id, revoked_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_refresh_token_parent_jwt_id", columnNames = "parent_jwt_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RefreshToken {

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

    public static RefreshToken create(Long userId, String jwtId, Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(
                null, userId, jwtId, null, issuedAt, expiresAt, null, null, null, LocalDateTime.now()
        );
    }

    public static RefreshToken createChild(
            Long userId, String jwtId, String parentJwtId, Instant issuedAt, Instant expiresAt
    ) {
        return new RefreshToken(
                null, userId, jwtId, parentJwtId, issuedAt, expiresAt, null, null, null, LocalDateTime.now()
        );
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
        return new RefreshToken(
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
}
