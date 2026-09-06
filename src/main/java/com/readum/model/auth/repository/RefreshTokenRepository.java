package com.readum.model.auth.repository;

import com.readum.model.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByUserIdAndJwtId(Long userId, String jwtId);

    Optional<RefreshToken> findByParentJwtId(String parentJwtId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken refreshToken
               set refreshToken.rotatedAt = :now
                 , refreshToken.graceExpiresAt = :graceUntil
             where refreshToken.id = :id
               and refreshToken.rotatedAt is null
               and refreshToken.revokedAt is null
               and refreshToken.expiresAt > :now
            """)
    int rotate(
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("graceUntil") Instant graceUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken refreshToken
               set refreshToken.revokedAt = :now
             where refreshToken.userId = :userId
               and refreshToken.revokedAt is null
            """)
    int revokeAllByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );
}
