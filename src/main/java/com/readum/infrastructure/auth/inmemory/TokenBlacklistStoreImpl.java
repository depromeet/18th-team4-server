package com.readum.infrastructure.auth.inmemory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.readum.domain.auth.out.TokenBlacklistStore;
import com.readum.domain.auth.jwt.JwtProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TokenBlacklistStoreImpl implements TokenBlacklistStore {

    private final Cache<String, Long> cache;

    public TokenBlacklistStoreImpl(JwtProperties jwtProperties) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(jwtProperties.blacklistMaxSize())
                .expireAfter(new PerEntryTtlExpiry())
                .build();
    }

    @Override
    public void add(String accessTokenJwtId, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        cache.put(accessTokenJwtId, ttl.toNanos());
    }

    @Override
    public boolean contains(String accessTokenJwtId) {
        return cache.getIfPresent(accessTokenJwtId) != null;
    }

    private static final class PerEntryTtlExpiry implements Expiry<String, Long> {

        @Override
        public long expireAfterCreate(String key, Long ttlNanos, long currentTime) {
            return ttlNanos;
        }

        @Override
        public long expireAfterUpdate(String key, Long ttlNanos, long currentTime, long currentDuration) {
            return ttlNanos;
        }

        @Override
        public long expireAfterRead(String key, Long ttlNanos, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
