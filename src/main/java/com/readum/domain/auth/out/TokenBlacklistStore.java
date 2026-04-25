package com.readum.domain.auth.out;

import java.time.Duration;

public interface TokenBlacklistStore {

    void add(String accessTokenJwtId, Duration ttl);

    boolean contains(String accessTokenJwtId);
}
