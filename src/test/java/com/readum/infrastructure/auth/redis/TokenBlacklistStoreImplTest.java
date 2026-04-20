package com.readum.infrastructure.auth.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistStoreImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TokenBlacklistStoreImpl tokenBlacklistStore;

    @Test
    void contains_는_Redis_장애_발생_시_fail_open_으로_false_를_반환한다() {
        given(redisTemplate.hasKey(anyString()))
                .willThrow(new RedisConnectionFailureException("Redis down"));

        boolean result = tokenBlacklistStore.contains("jwt-id");

        assertThat(result).isFalse();
    }

    @Test
    void add_는_Redis_장애_발생_시_예외를_전파하지_않고_삼킨다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(new RedisConnectionFailureException("Redis down"))
                .given(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> tokenBlacklistStore.add("jwt-id", Duration.ofMinutes(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void add_는_TTL_이_0_또는_음수면_Redis_호출_없이_종료한다() {
        tokenBlacklistStore.add("jwt-id", Duration.ZERO);
        tokenBlacklistStore.add("jwt-id", Duration.ofSeconds(-1));

        verify(redisTemplate, org.mockito.Mockito.never()).opsForValue();
    }
}
