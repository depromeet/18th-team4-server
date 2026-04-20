package com.readum.model.auth.repository;

import com.readum.model.auth.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByUserIdAndJwtId(Long userId, String jwtId);

    Optional<RefreshTokenEntity> findByParentJwtId(String parentJwtId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity r
               set r.rotatedAt = :now,
                   r.graceExpiresAt = :graceUntil
             where r.id = :id
               and r.rotatedAt is null
               and r.revokedAt is null
               and r.expiresAt > :now
            """)
    int rotate(
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("graceUntil") Instant graceUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity r
               set r.revokedAt = :now
             where r.userId = :userId
               and r.revokedAt is null
            """)
    int revokeAllByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );
}
