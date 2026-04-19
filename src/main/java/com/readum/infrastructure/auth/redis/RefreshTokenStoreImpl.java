package com.readum.infrastructure.auth.redis;

import com.readum.domain.auth.out.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RefreshTokenStoreImpl implements RefreshTokenStore {

    private static final String CURRENT_KEY_PREFIX = "rt:";
    private static final String GRACE_KEY_PREFIX = "rt:grace:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(Long userId, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(currentKey(userId), refreshToken, ttl);
    }

    @Override
    public Optional<String> findCurrent(Long userId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(currentKey(userId)));
    }

    @Override
    public boolean existsInGrace(Long userId, String refreshTokenJwtId) {
        Boolean exists = redisTemplate.hasKey(graceKey(userId, refreshTokenJwtId));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void rotate(
            Long userId,
            String oldRefreshTokenJwtId,
            String newRefreshToken,
            Duration newRefreshTokenTtl,
            Duration graceTtl
    ) {
        redisTemplate.opsForValue().set(graceKey(userId, oldRefreshTokenJwtId), "1", graceTtl);
        redisTemplate.opsForValue().set(currentKey(userId), newRefreshToken, newRefreshTokenTtl);
    }

    @Override
    public void deleteAll(Long userId) {
        redisTemplate.delete(currentKey(userId));
        var graceKeys = redisTemplate.keys(GRACE_KEY_PREFIX + userId + ":*");
        if (graceKeys != null && !graceKeys.isEmpty()) {
            redisTemplate.delete(graceKeys);
        }
    }

    private String currentKey(Long userId) {
        return CURRENT_KEY_PREFIX + userId;
    }

    private String graceKey(Long userId, String jwtId) {
        return GRACE_KEY_PREFIX + userId + ":" + jwtId;
    }
}
