package com.readum.infrastructure.auth.inmemory;

import com.readum.domain.auth.jwt.JwtProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TokenBlacklistStoreImplTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "dGVzdHNlY3JldHRlc3RzZWNyZXR0ZXN0c2VjcmV0dGVzdA==",
            "readum-test",
            Duration.ofMinutes(30),
            Duration.ofDays(14),
            Duration.ofSeconds(3),
            10_000L
    );

    @Test
    void add_이후_contains_는_true_를_반환한다() {
        TokenBlacklistStoreImpl store = new TokenBlacklistStoreImpl(PROPERTIES);
        store.add("jwt-id", Duration.ofMinutes(15));

        assertThat(store.contains("jwt-id")).isTrue();
    }

    @Test
    void 등록되지_않은_jwt_id_는_contains_에서_false_를_반환한다() {
        TokenBlacklistStoreImpl store = new TokenBlacklistStoreImpl(PROPERTIES);

        assertThat(store.contains("missing")).isFalse();
    }

    @Test
    void TTL_이_경과하면_contains_는_false_를_반환한다() {
        TokenBlacklistStoreImpl store = new TokenBlacklistStoreImpl(PROPERTIES);
        store.add("jwt-id", Duration.ofMillis(100));

        await().atMost(Duration.ofSeconds(2))
                .pollDelay(Duration.ofMillis(150))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> !store.contains("jwt-id"));
    }

    @Test
    void TTL_이_0_또는_음수면_저장되지_않는다() {
        TokenBlacklistStoreImpl store = new TokenBlacklistStoreImpl(PROPERTIES);
        store.add("zero", Duration.ZERO);
        store.add("negative", Duration.ofSeconds(-1));

        assertThat(store.contains("zero")).isFalse();
        assertThat(store.contains("negative")).isFalse();
    }
}
