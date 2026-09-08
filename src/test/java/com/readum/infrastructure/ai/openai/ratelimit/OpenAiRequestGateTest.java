package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
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
    private static final int BURST_SECONDS = 10;
    private static final String CHAT_REQUEST_BUCKET_KEY = "ai:global:chat:" + MODEL + ":bucket:rpm";
    private static final String CHAT_TOKEN_BUCKET_KEY = "ai:global:chat:" + MODEL + ":bucket:tpm";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private OpenAiRequestGate gate;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        gate = new OpenAiRequestGate(stringRedisTemplate, properties());
    }

    /** 9000 rpm · 180000 tpm · burst 10초 — 다섯 프로젝트 모두 같은 한도, moderation 은 한도 없음. */
    private static OpenAiProjectProperties properties() {
        Map<String, OpenAiProjectProperties.ModelLimit> limits =
                Map.of(MODEL, new OpenAiProjectProperties.ModelLimit(9000, 180000L));
        return new OpenAiProjectProperties(
                Map.of(
                        OpenAiProject.CHAT, new OpenAiProjectProperties.Project("chat-key", limits),
                        OpenAiProject.MODERATION, new OpenAiProjectProperties.Project("moderation-key", Map.of()),
                        OpenAiProject.SUMMARY, new OpenAiProjectProperties.Project("summary-key", limits),
                        OpenAiProject.CONTEXT_SUMMARY, new OpenAiProjectProperties.Project("context-summary-key", limits),
                        OpenAiProject.TITLE, new OpenAiProjectProperties.Project("title-key", limits)
                ),
                new OpenAiProjectProperties.Gate(BURST_SECONDS, 1000, 300)
        );
    }

    // --- 스크립트 호출 도우미 — RedisScript 제네릭 때문에 붙는 unchecked 경고를 여기 모아 둔다 ---------------

    @SuppressWarnings("unchecked")
    private void givenScriptReturns(Long scriptResult) {
        given(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(scriptResult);
    }

    @SuppressWarnings("unchecked")
    private void givenScriptFails(RuntimeException failure) {
        given(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .willThrow(failure);
    }

    @SuppressWarnings("unchecked")
    private void verifyScriptNeverCalled() {
        verify(stringRedisTemplate, never()).execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 스크립트에 넘어간 KEYS 를 꺼낸다. */
    @SuppressWarnings("unchecked")
    private List<String> captureScriptKeys() {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(RedisScript.class), keysCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        return keysCaptor.getValue();
    }

    /** 스크립트에 넘어간 ARGV 를 순서대로 꺼낸다. */
    @SuppressWarnings("unchecked")
    private List<Object> captureScriptArguments() {
        ArgumentCaptor<Object> argumentsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(stringRedisTemplate).execute(
                any(RedisScript.class), anyList(),
                argumentsCaptor.capture(), argumentsCaptor.capture(), argumentsCaptor.capture(),
                argumentsCaptor.capture(), argumentsCaptor.capture(), argumentsCaptor.capture(),
                argumentsCaptor.capture());
        return argumentsCaptor.getAllValues();
    }

    @Test
    void 스크립트가_0_을_반환하면_프로젝트_모델_토큰을_담은_계상_내역과_함께_Permitted() {
        givenScriptReturns(0L);

        OpenAiRequestGate.Decision decision = gate.tryAcquire(OpenAiProject.CHAT, MODEL, 5000);

        assertThat(decision).isInstanceOf(OpenAiRequestGate.Decision.Permitted.class);
        OpenAiRequestGate.GateReservation reservation =
                ((OpenAiRequestGate.Decision.Permitted) decision).reservation();
        assertThat(reservation.project()).isEqualTo(OpenAiProject.CHAT);
        assertThat(reservation.model()).isEqualTo(MODEL);
        assertThat(reservation.estimatedTokens()).isEqualTo(5000);
    }

    @Test
    void 스크립트가_양수를_반환하면_그_ms_를_RetryAfter_로_담아_RATE_BUDGET_으로_거절한다() {
        givenScriptReturns(750L);

        OpenAiRequestGate.Decision decision = gate.tryAcquire(OpenAiProject.CHAT, MODEL, 5000);

        assertThat(decision).isInstanceOf(OpenAiRequestGate.Decision.Rejected.class);
        OpenAiRequestGate.Decision.Rejected rejected = (OpenAiRequestGate.Decision.Rejected) decision;
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMillis(750));
        assertThat(rejected.reason()).isEqualTo(OpenAiRequestGate.RejectReason.RATE_BUDGET);
    }

    @Test
    void 스크립트_응답이_없으면_계상_내역_없이_허용한다() {
        givenScriptReturns(null);

        assertThat(gate.tryAcquire(OpenAiProject.CHAT, MODEL, 5000))
                .isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
    }

    @Test
    void Redis_장애면_계상_내역_없이_허용한다() {
        givenScriptFails(new QueryTimeoutException("timeout"));

        assertThat(gate.tryAcquire(OpenAiProject.CHAT, MODEL, 5000))
                .isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
    }

    @Test
    void 프로젝트에_모델_한도가_없으면_스크립트를_부르지_않고_계상_내역_없이_허용한다() {
        assertThat(gate.tryAcquire(OpenAiProject.MODERATION, MODEL, 100))
                .isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);
        assertThat(gate.tryAcquire(OpenAiProject.CHAT, "unknown-model", 100))
                .isInstanceOf(OpenAiRequestGate.Decision.PermittedUncounted.class);

        verifyScriptNeverCalled();
    }

    @Test
    void quota_쿨다운_중이면_스크립트를_부르지_않고_QUOTA_COOLDOWN_으로_거절한다() {
        given(stringRedisTemplate.getExpire(contains(":quota-cooldown"), eq(TimeUnit.SECONDS))).willReturn(120L);

        OpenAiRequestGate.Decision decision = gate.tryAcquire(OpenAiProject.CHAT, MODEL, 5000);

        assertThat(decision).isInstanceOf(OpenAiRequestGate.Decision.Rejected.class);
        OpenAiRequestGate.Decision.Rejected rejected = (OpenAiRequestGate.Decision.Rejected) decision;
        assertThat(rejected.reason()).isEqualTo(OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN);
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(120));
        verifyScriptNeverCalled();
    }

    /** 게이트가 Lua 스크립트에 넘기는 KEYS·ARGV 의 순서와 값 — 스크립트가 읽는 자리와 맞는지 지킨다. */
    @Test
    void 스크립트에_프로젝트별_버킷_키와_한도에서_계산한_버킷_크기_보충_속도를_전달한다() {
        givenScriptReturns(0L);
        long beforeMillis = System.currentTimeMillis();

        gate.tryAcquire(OpenAiProject.CHAT, MODEL, 5000);

        long afterMillis = System.currentTimeMillis();
        assertThat(captureScriptKeys()).containsExactly(CHAT_REQUEST_BUCKET_KEY, CHAT_TOKEN_BUCKET_KEY);
        List<Object> scriptArgs = captureScriptArguments();
        assertThat(Long.parseLong((String) scriptArgs.get(0))).isBetween(beforeMillis, afterMillis);
        // 9000 rpm → 0.15 건/ms, 버킷 = 0.15 × 10초 = 1500 건
        assertThat(scriptArgs.get(1)).isEqualTo("1500");
        assertThat(Double.parseDouble((String) scriptArgs.get(2))).isCloseTo(0.15, within(1e-9));
        // 180000 tpm → 3 토큰/ms, 버킷 = 3 × 10초 = 30000 토큰
        assertThat(scriptArgs.get(3)).isEqualTo("30000");
        assertThat(Double.parseDouble((String) scriptArgs.get(4))).isCloseTo(3.0, within(1e-9));
        assertThat(scriptArgs.get(5)).isEqualTo("5000");
        // 최소 TTL = burst 의 두 배
        assertThat(scriptArgs.get(6)).isEqualTo(String.valueOf(Duration.ofSeconds(BURST_SECONDS * 2L).toMillis()));
    }

    @Test
    void 프로젝트가_다르면_같은_모델이라도_다른_버킷_키를_쓴다() {
        givenScriptReturns(0L);

        gate.tryAcquire(OpenAiProject.CONTEXT_SUMMARY, MODEL, 5000);

        assertThat(captureScriptKeys()).containsExactly(
                "ai:global:context-summary:" + MODEL + ":bucket:rpm",
                "ai:global:context-summary:" + MODEL + ":bucket:tpm");
    }

    @Test
    void 보상은_두_버킷_hash_의_tokens_필드에_요청_1_과_추정_토큰을_더하고_TTL_을_다시_건다() {
        gate.compensate(new OpenAiRequestGate.GateReservation(OpenAiProject.CHAT, MODEL, 5000));

        verify(hashOperations).increment(CHAT_REQUEST_BUCKET_KEY, "tokens", 1.0d);
        verify(hashOperations).increment(CHAT_TOKEN_BUCKET_KEY, "tokens", 5000.0d);
        verify(stringRedisTemplate).expire(CHAT_REQUEST_BUCKET_KEY, Duration.ofSeconds(BURST_SECONDS * 2L));
        verify(stringRedisTemplate).expire(CHAT_TOKEN_BUCKET_KEY, Duration.ofSeconds(BURST_SECONDS * 2L));
    }

    @Test
    void 보상_중_Redis_장애는_던지지_않고_삼킨다() {
        given(hashOperations.increment(anyString(), any(), anyDouble()))
                .willThrow(new QueryTimeoutException("timeout"));

        assertThatCode(() -> gate.compensate(new OpenAiRequestGate.GateReservation(OpenAiProject.CHAT, MODEL, 5000)))
                .doesNotThrowAnyException();
    }

    @Test
    void enterQuotaCooldown_은_프로젝트와_무관한_모델_키에_TTL_로_심는다() {
        gate.enterQuotaCooldown(MODEL, Duration.ofSeconds(300));

        verify(valueOperations).set("ai:global:" + MODEL + ":quota-cooldown", "1", Duration.ofSeconds(300));
    }

    @Test
    void isInQuotaCooldown_은_키_존재를_반영한다() {
        given(stringRedisTemplate.hasKey("ai:global:" + MODEL + ":quota-cooldown")).willReturn(true);

        assertThat(gate.isInQuotaCooldown(MODEL)).isTrue();
    }

    @Test
    void isInQuotaCooldown_은_Redis_장애면_false_로_통과시킨다() {
        given(stringRedisTemplate.hasKey(anyString())).willThrow(new QueryTimeoutException("timeout"));

        assertThat(gate.isInQuotaCooldown(MODEL)).isFalse();
    }
}
