package com.readum.infrastructure.auth.redis;

import com.readum.domain.auth.out.TokenBlacklistStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
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
        try {
            redisTemplate.opsForValue().set(key(accessTokenJwtId), "1", ttl);
        } catch (DataAccessException e) {
            log.error("Redis 연결 실패 - Blacklist 추가 불가, Access Token TTL({}초) 후 자연 만료 예상 jwtId={}",
                    ttl.toSeconds(), accessTokenJwtId, e);
        }
    }

    @Override
    public boolean contains(String accessTokenJwtId) {
        try {
            Boolean exists = redisTemplate.hasKey(key(accessTokenJwtId));
            return Boolean.TRUE.equals(exists);
        } catch (DataAccessException e) {
            log.warn("Redis 연결 실패 - Blacklist 조회 불가, fail-open 처리 jwtId={}", accessTokenJwtId);
            return false;
        }
    }

    private String key(String jwtId) {
        return KEY_PREFIX + jwtId;
    }
}
