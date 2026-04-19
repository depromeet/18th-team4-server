package com.readum.domain.auth.out;

import java.time.Duration;
import java.util.Optional;

public interface RefreshTokenStore {

    void save(Long userId, String refreshToken, Duration ttl);

    Optional<String> findCurrent(Long userId);

    boolean existsInGrace(Long userId, String refreshTokenJwtId);

    void rotate(
            Long userId,
            String oldRefreshTokenJwtId,
            String newRefreshToken,
            Duration newRefreshTokenTtl,
            Duration graceTtl
    );

    void deleteAll(Long userId);
}
