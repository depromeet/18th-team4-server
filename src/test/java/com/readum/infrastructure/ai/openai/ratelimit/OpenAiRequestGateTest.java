package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OpenAiRequestGateTest {

    private static final String MODEL = "gpt-4o-mini";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OpenAiRequestGate gate;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        gate = new OpenAiRequestGate(stringRedisTemplate,
                new OpenAiGateProperties(Map.of(MODEL, new OpenAiGateProperties.ModelLimit(9000, 180000L))));
    }

    @Test
    void 예산_이내면_Permitted() {
        given(valueOperations.increment(contains(":rpm:"), anyLong())).willReturn(10L);
        given(valueOperations.increment(contains(":tpm:"), anyLong())).willReturn(5000L);

        assertThat(gate.tryAcquire(MODEL, 5000)).isInstanceOf(OpenAiRequestGate.Decision.Permitted.class);
    }

    @Test
    void TPM_초과면_두_카운터를_되돌리고_Rejected_와_다음_분까지의_RetryAfter() {
        given(valueOperations.increment(contains(":rpm:"), anyLong())).willReturn(10L);
        given(valueOperations.increment(contains(":tpm:"), anyLong())).willReturn(180001L);

        OpenAiRequestGate.Decision decision = gate.tryAcquire(MODEL, 5000);

        assertThat(decision).isInstanceOf(OpenAiRequestGate.Decision.Rejected.class);
        Duration retryAfter = ((OpenAiRequestGate.Decision.Rejected) decision).retryAfter();
        assertThat(retryAfter).isPositive();
        assertThat(retryAfter).isLessThanOrEqualTo(Duration.ofSeconds(60));
        verify(valueOperations).increment(contains(":rpm:"), eq(-1L));
        verify(valueOperations).increment(contains(":tpm:"), eq(-5000L));
    }

    @Test
    void RPM_초과도_거절한다() {
        given(valueOperations.increment(contains(":rpm:"), anyLong())).willReturn(9001L);
        given(valueOperations.increment(contains(":tpm:"), anyLong())).willReturn(100L);

        assertThat(gate.tryAcquire(MODEL, 100)).isInstanceOf(OpenAiRequestGate.Decision.Rejected.class);
    }

    @Test
    void 창의_첫_기록이면_TTL_을_건다() {
        given(valueOperations.increment(contains(":rpm:"), anyLong())).willReturn(1L);   // == delta → 첫 기록
        given(valueOperations.increment(contains(":tpm:"), anyLong())).willReturn(5000L);

        gate.tryAcquire(MODEL, 5000);

        verify(stringRedisTemplate).expire(contains(":rpm:"), eq(Duration.ofMinutes(2)));
        verify(stringRedisTemplate).expire(contains(":tpm:"), eq(Duration.ofMinutes(2)));
    }

    @Test
    void Redis_장애면_허용한다() {
        given(valueOperations.increment(anyString(), anyLong())).willThrow(new QueryTimeoutException("timeout"));

        assertThat(gate.tryAcquire(MODEL, 5000)).isInstanceOf(OpenAiRequestGate.Decision.Permitted.class);
    }

    @Test
    void 한도_미설정_모델은_허용한다() {
        assertThat(gate.tryAcquire("unknown-model", 100)).isInstanceOf(OpenAiRequestGate.Decision.Permitted.class);
    }
}
