package com.readum.infrastructure.auth.redis;

import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RefreshTokenStoreImpl refreshTokenStore;

    @Test
    void save_는_Redis_장애_시_ServiceUnavailableException_을_던진다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(new RedisConnectionFailureException("Redis down"))
                .given(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> refreshTokenStore.save(1L, "refresh-token", Duration.ofDays(14)))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void findCurrent_는_Redis_장애_시_ServiceUnavailableException_을_던진다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString()))
                .willThrow(new RedisConnectionFailureException("Redis down"));

        assertThatThrownBy(() -> refreshTokenStore.findCurrent(1L))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void existsInGrace_는_Redis_장애_시_ServiceUnavailableException_을_던진다() {
        given(redisTemplate.hasKey(anyString()))
                .willThrow(new RedisConnectionFailureException("Redis down"));

        assertThatThrownBy(() -> refreshTokenStore.existsInGrace(1L, "jwt-id"))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void rotate_는_Redis_장애_시_ServiceUnavailableException_을_던진다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(new RedisConnectionFailureException("Redis down"))
                .given(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> refreshTokenStore.rotate(
                1L, "old-jti", "new-refresh-token", Duration.ofDays(14), Duration.ofSeconds(3)
        ))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void deleteAll_은_Redis_장애_시_fail_open_으로_예외를_삼킨다() {
        willThrow(new RedisConnectionFailureException("Redis down"))
                .given(redisTemplate).delete(anyString());

        assertThatCode(() -> refreshTokenStore.deleteAll(1L))
                .doesNotThrowAnyException();
    }
}
