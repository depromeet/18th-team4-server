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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
                new OpenAiGateProperties(Map.of(MODEL, new OpenAiGateProperties.ModelLimit(9000, 180000L)), 300));
    }

    @Test
    void 예산_이내면_계상_내역을_담은_Permitted() {
        given(valueOperations.increment(contains(":rpm:"), anyLong())).willReturn(10L);
        given(valueOperations.increment(contains(":tpm:"), anyLong())).willReturn(5000L);
        long minuteBefore = Instant.now().getEpochSecond() / 60;

        OpenAiRequestGate.Decision decision = gate.tryAcquire(MODEL, 5000);

        long minuteAfter = Instant.now().getEpochSecond() / 60;
        assertThat(decision).isInstanceOf(OpenAiRequestGate.Decision.Permitted.class);
        OpenAiRequestGate.GateReservation reservation =
                ((OpenAiRequestGate.Decision.Permitted) decision).reservation();
        assertThat(reservation.model()).isEqualTo(MODEL);
        assertThat(reservation.estimatedTokens()).isEqualTo(5000);
        assertThat(reservation.epochMinute()).isBetween(minuteBefore, minuteAfter);
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
    void Redis_장애면_계상_내역_없이_허용한다() {
        given(valueOperations.increment(anyString(), anyLong())).willThrow(new QueryTimeoutException("timeout"));

        assertThat(gate.tryAcquire(MODEL, 5000))
                .isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
    }

    @Test
    void 한도_미설정_모델은_계상_내역_없이_허용한다() {
        assertThat(gate.tryAcquire("unknown-model", 100))
                .isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
    }

    @Test
    void INCRBY_응답이_없으면_계상_내역_없이_허용한다() {
        given(valueOperations.increment(contains(":rpm:"), anyLong())).willReturn(null);
        given(valueOperations.increment(contains(":tpm:"), anyLong())).willReturn(5000L);

        assertThat(gate.tryAcquire(MODEL, 5000))
                .isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
    }

    @Test
    void 예산_초과_거절의_사유는_RATE_BUDGET() {
        given(valueOperations.increment(contains(":rpm:"), anyLong())).willReturn(10L);
        given(valueOperations.increment(contains(":tpm:"), anyLong())).willReturn(180001L);

        OpenAiRequestGate.Decision decision = gate.tryAcquire(MODEL, 5000);

        assertThat(decision).isInstanceOf(OpenAiRequestGate.Decision.Rejected.class);
        assertThat(((OpenAiRequestGate.Decision.Rejected) decision).reason())
                .isEqualTo(OpenAiRequestGate.RejectReason.RATE_BUDGET);
    }

    @Test
    void quota_쿨다운_중이면_카운터를_세지_않고_QUOTA_COOLDOWN_으로_거절한다() {
        given(stringRedisTemplate.getExpire(contains(":quota-cooldown"), eq(TimeUnit.SECONDS))).willReturn(120L);

        OpenAiRequestGate.Decision decision = gate.tryAcquire(MODEL, 5000);

        assertThat(decision).isInstanceOf(OpenAiRequestGate.Decision.Rejected.class);
        OpenAiRequestGate.Decision.Rejected rejected = (OpenAiRequestGate.Decision.Rejected) decision;
        assertThat(rejected.reason()).isEqualTo(OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN);
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(120));
        verify(valueOperations, never()).increment(contains(":rpm:"), anyLong());
    }

    @Test
    void enterQuotaCooldown_은_TTL_로_키를_심는다() {
        gate.enterQuotaCooldown(MODEL, Duration.ofSeconds(300));

        verify(valueOperations).set(contains(":quota-cooldown"), eq("1"), eq(Duration.ofSeconds(300)));
    }

    @Test
    void isInQuotaCooldown_은_키_존재를_반영한다() {
        given(stringRedisTemplate.hasKey(contains(":quota-cooldown"))).willReturn(true);

        assertThat(gate.isInQuotaCooldown(MODEL)).isTrue();
    }

    @Test
    void 보상_차감은_예약이_담고_있는_분_키에서_요청_1과_추정_토큰을_되돌린다() {
        long reservedMinute = 29_000_000L;

        gate.compensate(new OpenAiRequestGate.GateReservation(MODEL, reservedMinute, 5000));

        verify(valueOperations).increment("ai:global:" + MODEL + ":rpm:" + reservedMinute, -1L);
        verify(valueOperations).increment("ai:global:" + MODEL + ":tpm:" + reservedMinute, -5000L);
    }

    @Test
    void 보상_차감_후에는_두_분_키에_TTL_을_다시_건다() {
        // 만료된 분 키에 DECRBY 하면 TTL 없는 음수 키가 새로 생기므로, 잔존 방지용 TTL 재설정을 검증한다.
        long reservedMinute = 29_000_000L;

        gate.compensate(new OpenAiRequestGate.GateReservation(MODEL, reservedMinute, 5000));

        verify(stringRedisTemplate).expire("ai:global:" + MODEL + ":rpm:" + reservedMinute, Duration.ofMinutes(2));
        verify(stringRedisTemplate).expire("ai:global:" + MODEL + ":tpm:" + reservedMinute, Duration.ofMinutes(2));
    }

    @Test
    void 보상_차감_중_Redis_장애는_던지지_않고_삼킨다() {
        given(valueOperations.increment(anyString(), anyLong())).willThrow(new QueryTimeoutException("timeout"));

        assertThatCode(() -> gate.compensate(new OpenAiRequestGate.GateReservation(MODEL, 29_000_000L, 5000)))
                .doesNotThrowAnyException();
    }
}
