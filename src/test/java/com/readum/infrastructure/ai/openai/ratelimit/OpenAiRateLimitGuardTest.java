package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OpenAiRateLimitGuardTest {

    private static final String MODEL = "gpt-4o-mini";
    private static final int ESTIMATED_TOKENS = 1000;
    private static final long CHAT_MAX_WAIT_MILLIS = 1000;

    private static final OpenAiRequestGate.GateReservation CHAT_RESERVATION =
            new OpenAiRequestGate.GateReservation(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS);
    private static final OpenAiRequestGate.Decision PERMITTED =
            new OpenAiRequestGate.Decision.Permitted(CHAT_RESERVATION);

    @Mock
    private OpenAiRequestGate gate;

    /** 실제로 잠들지 않고 요청받은 대기 시간만 기록한다. */
    private final List<Duration> recordedSleeps = new ArrayList<>();

    private OpenAiRateLimitGuard guard;

    @BeforeEach
    void setUp() {
        guard = new OpenAiRateLimitGuard(gate, properties(), recordedSleeps::add);
    }

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
                new OpenAiProjectProperties.Gate(10, CHAT_MAX_WAIT_MILLIS, 300)
        );
    }

    private static OpenAiRequestGate.Decision rateBudgetRejected(long retryAfterMillis) {
        return new OpenAiRequestGate.Decision.Rejected(
                Duration.ofMillis(retryAfterMillis), OpenAiRequestGate.RejectReason.RATE_BUDGET);
    }

    @Test
    void 게이트가_통과시키면_계상_내역을_반환한다() {
        given(gate.tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS)).willReturn(PERMITTED);

        assertThat(guard.acquireOrThrow(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS)).contains(CHAT_RESERVATION);
        assertThat(recordedSleeps).isEmpty();
    }

    @Test
    void 계상_없는_통과면_빈_계상_내역을_반환하고_던지지_않는다() {
        given(gate.tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS))
                .willReturn(new OpenAiRequestGate.Decision.PermittedUncounted());

        assertThat(guard.acquireOrThrow(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS)).isEmpty();
        assertThat(recordedSleeps).isEmpty();
    }

    @Test
    void quota_쿨다운_거절은_채팅이라도_기다리지_않고_즉시_AI_QUOTA_EXHAUSTED_로_던진다() {
        Duration retryAfter = Duration.ofMillis(500);   // 대기 상한 안이지만 쿨다운은 기다릴 종류가 아니다
        given(gate.tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS)).willReturn(
                new OpenAiRequestGate.Decision.Rejected(retryAfter, OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN));

        assertThatThrownBy(() -> guard.acquireOrThrow(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS))
                .isInstanceOfSatisfying(TooManyRequestsException.class, thrown -> {
                    assertThat(thrown.getErrorCode()).isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED);
                    assertThat(thrown.getRateLimitInfo().retryAfter()).isEqualTo(retryAfter);
                });
        assertThat(recordedSleeps).isEmpty();
        verify(gate, times(1)).tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS);
    }

    @Test
    void 채팅은_기다릴_시간이_상한_안이면_그만큼_잠들었다_다시_확보해_통과한다() {
        given(gate.tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS))
                .willReturn(rateBudgetRejected(400), PERMITTED);

        assertThat(guard.acquireOrThrow(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS)).contains(CHAT_RESERVATION);

        assertThat(recordedSleeps).containsExactly(Duration.ofMillis(400));
        verify(gate, times(2)).tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS);
    }

    @Test
    void 채팅이라도_기다릴_시간이_상한을_넘으면_잠들지_않고_AI_RATE_LIMIT_BURST_로_던진다() {
        given(gate.tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS))
                .willReturn(rateBudgetRejected(CHAT_MAX_WAIT_MILLIS + 1));

        assertThatThrownBy(() -> guard.acquireOrThrow(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS))
                .isInstanceOfSatisfying(TooManyRequestsException.class, thrown -> {
                    assertThat(thrown.getErrorCode()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);
                    assertThat(thrown.getRateLimitInfo().retryAfter())
                            .isEqualTo(Duration.ofMillis(CHAT_MAX_WAIT_MILLIS + 1));
                });
        assertThat(recordedSleeps).isEmpty();
    }

    @Test
    void 채팅이_여러_번_기다려_합이_상한을_넘게_되면_더_잠들지_않고_던진다() {
        // 600 + 300 = 900 까지는 기다리지만, 세 번째 200 은 합이 1100 으로 상한을 넘으므로 던진다.
        given(gate.tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS))
                .willReturn(rateBudgetRejected(600), rateBudgetRejected(300), rateBudgetRejected(200));

        assertThatThrownBy(() -> guard.acquireOrThrow(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS))
                .isInstanceOfSatisfying(TooManyRequestsException.class, thrown -> {
                    assertThat(thrown.getErrorCode()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);
                    assertThat(thrown.getRateLimitInfo().retryAfter()).isEqualTo(Duration.ofMillis(200));
                });
        assertThat(recordedSleeps).containsExactly(Duration.ofMillis(600), Duration.ofMillis(300));
        verify(gate, times(3)).tryAcquire(OpenAiProject.CHAT, MODEL, ESTIMATED_TOKENS);
    }

    @Test
    void 채팅이_아닌_프로젝트는_기다릴_시간이_짧아도_잠들지_않고_즉시_던진다() {
        for (OpenAiProject project : List.of(OpenAiProject.SUMMARY, OpenAiProject.CONTEXT_SUMMARY, OpenAiProject.TITLE)) {
            given(gate.tryAcquire(project, MODEL, ESTIMATED_TOKENS)).willReturn(rateBudgetRejected(1));

            assertThatThrownBy(() -> guard.acquireOrThrow(project, MODEL, ESTIMATED_TOKENS))
                    .isInstanceOfSatisfying(TooManyRequestsException.class, thrown -> {
                        assertThat(thrown.getErrorCode()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);
                        assertThat(thrown.getRateLimitInfo().retryAfter()).isEqualTo(Duration.ofMillis(1));
                    });
            verify(gate, times(1)).tryAcquire(project, MODEL, ESTIMATED_TOKENS);
        }
        assertThat(recordedSleeps).isEmpty();
    }

    @Test
    void 보상은_게이트에_그대로_위임한다() {
        guard.compensate(CHAT_RESERVATION);

        verify(gate).compensate(CHAT_RESERVATION);
    }
}
