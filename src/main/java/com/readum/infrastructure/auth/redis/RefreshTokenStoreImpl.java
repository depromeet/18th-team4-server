package com.readum.infrastructure.auth.redis;

import com.readum.domain.auth.out.RefreshTokenStore;
import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStoreImpl implements RefreshTokenStore {

    private static final String CURRENT_KEY_PREFIX = "rt:";
    private static final String GRACE_KEY_PREFIX = "rt:grace:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(Long userId, String refreshToken, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(currentKey(userId), refreshToken, ttl);
        } catch (DataAccessException e) {
            log.error("Redis 연결 실패 - Refresh Token 저장 불가 userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Optional<String> findCurrent(Long userId) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(currentKey(userId)));
        } catch (DataAccessException e) {
            log.error("Redis 연결 실패 - Refresh Token 조회 불가 userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public boolean existsInGrace(Long userId, String refreshTokenJwtId) {
        try {
            Boolean exists = redisTemplate.hasKey(graceKey(userId, refreshTokenJwtId));
            return Boolean.TRUE.equals(exists);
        } catch (DataAccessException e) {
            log.error("Redis 연결 실패 - Grace Refresh Token 조회 불가 userId={} jwtId={}", userId, refreshTokenJwtId, e);
            throw new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void rotate(
            Long userId,
            String oldRefreshTokenJwtId,
            String newRefreshToken,
            Duration newRefreshTokenTtl,
            Duration graceTtl
    ) {
        try {
            redisTemplate.opsForValue().set(graceKey(userId, oldRefreshTokenJwtId), "1", graceTtl);
            redisTemplate.opsForValue().set(currentKey(userId), newRefreshToken, newRefreshTokenTtl);
        } catch (DataAccessException e) {
            log.error("Redis 연결 실패 - Refresh Token rotation 불가 userId={} oldJwtId={}", userId, oldRefreshTokenJwtId, e);
            throw new ServiceUnavailableException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void deleteAll(Long userId) {
        try {
            redisTemplate.delete(currentKey(userId));
            List<String> graceKeys = scanGraceKeys(userId);
            if (!graceKeys.isEmpty()) {
                redisTemplate.delete(graceKeys);
            }
        } catch (DataAccessException e) {
            log.error("Redis 연결 실패 - Refresh Token 삭제 불가, Refresh TTL 후 자연 만료 예상 userId={}", userId, e);
        }
    }

    private List<String> scanGraceKeys(Long userId) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(GRACE_KEY_PREFIX + userId + ":*")
                .count(100)
                .build();
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    private String currentKey(Long userId) {
        return CURRENT_KEY_PREFIX + userId;
    }

    private String graceKey(Long userId, String jwtId) {
        return GRACE_KEY_PREFIX + userId + ":" + jwtId;
    }
}
