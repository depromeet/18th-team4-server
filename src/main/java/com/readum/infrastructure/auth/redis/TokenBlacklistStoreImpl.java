package com.readum.infrastructure.auth.redis;

import com.readum.domain.auth.out.TokenBlacklistStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class TokenBlacklistStoreImpl implements TokenBlacklistStore {

    private static final String KEY_PREFIX = "bl:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void add(String accessTokenJwtId, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.opsForValue().set(key(accessTokenJwtId), "1", ttl);
    }

    @Override
    public boolean contains(String accessTokenJwtId) {
        Boolean exists = redisTemplate.hasKey(key(accessTokenJwtId));
        return Boolean.TRUE.equals(exists);
    }

    private String key(String jwtId) {
        return KEY_PREFIX + jwtId;
    }
}
