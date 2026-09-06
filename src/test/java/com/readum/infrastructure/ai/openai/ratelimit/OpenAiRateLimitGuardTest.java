package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OpenAiRateLimitGuardTest {

    private static final String MODEL = "gpt-4o-mini";
    private static final int ESTIMATED_TOKENS = 1000;

    @Mock
    private OpenAiRequestGate gate;

    private OpenAiRateLimitGuard guard;

    @BeforeEach
    void setUp() {
        guard = new OpenAiRateLimitGuard(gate);
    }

    @Test
    void 게이트가_통과시키면_계상_내역을_반환한다() {
        OpenAiRequestGate.GateReservation reservation =
                new OpenAiRequestGate.GateReservation(MODEL, 29_000_000L, ESTIMATED_TOKENS);
        given(gate.tryAcquire(MODEL, ESTIMATED_TOKENS))
                .willReturn(new OpenAiRequestGate.Decision.Permitted(reservation));

        assertThat(guard.acquireOrThrow(MODEL, ESTIMATED_TOKENS)).contains(reservation);
    }

    @Test
    void 계상_없는_통과면_빈_계상_내역을_반환하고_던지지_않는다() {
        given(gate.tryAcquire(MODEL, ESTIMATED_TOKENS))
                .willReturn(new OpenAiRequestGate.Decision.PermittedUncounted());

        assertThat(guard.acquireOrThrow(MODEL, ESTIMATED_TOKENS)).isEmpty();
    }

    @Test
    void quota_쿨다운_거절이면_AI_QUOTA_EXHAUSTED_로_번역해_던진다() {
        Duration retryAfter = Duration.ofSeconds(120);
        given(gate.tryAcquire(MODEL, ESTIMATED_TOKENS)).willReturn(
                new OpenAiRequestGate.Decision.Rejected(retryAfter, OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN));

        assertThatThrownBy(() -> guard.acquireOrThrow(MODEL, ESTIMATED_TOKENS))
                .isInstanceOfSatisfying(TooManyRequestsException.class, thrown -> {
                    assertThat(thrown.getErrorCode()).isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED);
                    assertThat(thrown.getRateLimitInfo().retryAfter()).isEqualTo(retryAfter);
                });
    }

    @Test
    void 분당_예산_포화_거절이면_AI_RATE_LIMIT_BURST_로_번역해_던진다() {
        Duration retryAfter = Duration.ofSeconds(30);
        given(gate.tryAcquire(MODEL, ESTIMATED_TOKENS)).willReturn(
                new OpenAiRequestGate.Decision.Rejected(retryAfter, OpenAiRequestGate.RejectReason.RATE_BUDGET));

        assertThatThrownBy(() -> guard.acquireOrThrow(MODEL, ESTIMATED_TOKENS))
                .isInstanceOfSatisfying(TooManyRequestsException.class, thrown -> {
                    assertThat(thrown.getErrorCode()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);
                    assertThat(thrown.getRateLimitInfo().retryAfter()).isEqualTo(retryAfter);
                });
    }
}
