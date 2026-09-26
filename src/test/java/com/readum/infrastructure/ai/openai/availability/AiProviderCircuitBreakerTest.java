package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 차단기의 전이 규칙. 상태 기계 자체는 Resilience4j 의 것이므로 여기서 다시 확인하지 않고,
 * <b>우리가 그 위에 얹은 규칙</b>만 본다.
 *
 * <ul>
 *   <li>일반 호출은 확인 호출이 되지 않는다 — 차단 시간이 지나도 통과하지 못한다.</li>
 *   <li>확인은 전용 입구로만, 한 번에 하나만 나간다. 임차를 잃은 확인의 결과는 버린다.</li>
 *   <li>결제·인증은 시간만으로 풀리지 않는다. 공급자가 준 Retry-After 가 우리 기본값보다 길면 그것이 이긴다.</li>
 *   <li>기능은 서로 독립이고, 의존 기능이 막히면 본 기능도 시작하지 않는다.</li>
 *   <li>큐 있는 기능은 복구 직후 적체를 먼저 비운다.</li>
 * </ul>
 *
 * <p>시각은 앱이 넘기는 계약이라 잠들지 않고 시계를 앞으로 돌려 시간을 흘린다.
 */
class AiProviderCircuitBreakerTest {

    private static final long START_MILLIS = 1_700_000_000_000L;
    private static final long TRANSIENT_BLOCK_SECONDS = 30;
    private static final long RATE_LIMIT_BLOCK_SECONDS = 30;
    private static final long QUOTA_BLOCK_SECONDS = 300;
    private static final long PROBE_LEASE_SECONDS = 30;
    private static final int MINIMUM_CALLS = 10;

    private final AtomicLong now = new AtomicLong(START_MILLIS);
    private AiProviderCircuitBreaker circuitBreaker;

    @BeforeEach
    void freshCircuit() {
        now.set(START_MILLIS);
        circuitBreaker = new AiProviderCircuitBreaker(properties(), now::get);
    }

    private static AiAvailabilityProperties properties() {
        return new AiAvailabilityProperties(
                60, MINIMUM_CALLS, 50,
                TRANSIENT_BLOCK_SECONDS, RATE_LIMIT_BLOCK_SECONDS, QUOTA_BLOCK_SECONDS,
                300, 600, PROBE_LEASE_SECONDS);
    }

    // --- 기능 격리 -------------------------------------------------------------------------------

    @Test
    void 한_기능이_차단돼도_다른_기능은_그대로_나간다() {
        openImmediately(AiAvailability.Capability.SUMMARY, AiProviderFailureKind.RATE_LIMIT);

        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.SUMMARY))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.TITLE))
                .doesNotThrowAnyException();
    }

    @Test
    void 의존_기능이_차단되면_본_기능도_시작하지_않는다() {
        // 채팅은 입력 검토를 거쳐야 한 턴이 된다 — 검토가 막혔으면 응답 생성부터 시작할 이유가 없다.
        openImmediately(AiAvailability.Capability.MODERATION, AiProviderFailureKind.RATE_LIMIT);

        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.SUMMARY))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThat(circuitBreaker.isProcessingAllowed(AiAvailability.Capability.SUMMARY)).isFalse();
        assertThat(circuitBreaker.isProcessingAllowed(AiAvailability.Capability.CONTEXT_SUMMARY))
                .as("컨텍스트 요약은 검토에 기대지 않는다")
                .isTrue();
    }

    @Test
    void 채팅이_막혀_있으면_입력_검토_호출도_시작하지_않는다() {
        // 선행 처리가 채팅의 가용성을 먼저 확인하므로, 채팅이 막힌 동안에는 검토에 유료 호출이 나가지 않는다.
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.QUOTA);

        assertThatThrownBy(() -> circuitBreaker.requireNewWorkAllowed(AiAvailability.Capability.CHAT))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.MODERATION))
                .as("검토 자체는 멀쩡하다 — 막는 것은 채팅 경로의 진입이다")
                .doesNotThrowAnyException();
    }

    // --- 차단 기준 -------------------------------------------------------------------------------

    @Test
    void 한도_결제_인증은_한_건으로_바로_차단한다() {
        for (AiProviderFailureKind kind : List.of(
                AiProviderFailureKind.RATE_LIMIT, AiProviderFailureKind.QUOTA, AiProviderFailureKind.AUTH)) {
            freshCircuit();
            openImmediately(AiAvailability.Capability.CHAT, kind);

            assertThat(circuitBreaker.stateOf(AiAvailability.Capability.CHAT))
                    .as("%s 는 표본이 쌓이기를 기다리지 않는다", kind)
                    .isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Test
    void 일시_실패는_표본이_최소치에_닿기_전에는_차단하지_않는다() {
        // 예전의 "연속 5회" 규칙을 라이브러리의 표본·비율 판정에 맡긴 결과다 — 표본이 적으면 열리지 않는다.
        recordTransientFailures(AiAvailability.Capability.CHAT, MINIMUM_CALLS - 1);

        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .doesNotThrowAnyException();
    }

    @Test
    void 일시_실패가_표본_최소치를_넘어_절반을_넘기면_차단한다() {
        recordTransientFailures(AiAvailability.Capability.CHAT, MINIMUM_CALLS);

        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .isInstanceOf(AiDependencyUnavailableException.class);
    }

    @Test
    void 세지_않는_실패는_아무리_쌓여도_차단하지_않는다() {
        // 그 요청 하나의 입력 오류나 우리 쪽 오류는 공급자의 응답을 받아 본 것이 아니다.
        for (int attempt = 0; attempt < MINIMUM_CALLS * 3; attempt++) {
            AiProviderCircuitBreaker.CallPermit permit = circuitBreaker.admit(AiAvailability.Capability.CHAT);
            circuitBreaker.recordFailure(
                    permit, AiProviderFailureClassifier.Classification.of(AiProviderFailureKind.NOT_COUNTED));
        }

        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .doesNotThrowAnyException();
    }

    @Test
    void 공급자가_준_Retry_After_가_기본_차단_시간보다_길면_그것이_이긴다() {
        AiProviderCircuitBreaker.CallPermit permit = circuitBreaker.admit(AiAvailability.Capability.CHAT);
        circuitBreaker.recordFailure(permit, new AiProviderFailureClassifier.Classification(
                AiProviderFailureKind.RATE_LIMIT, Duration.ofSeconds(1200)));

        // 상한(600초)으로 자르면 공급자가 "아직 오지 마라" 고 한 시각 안에 두드려 똑같이 거절당한다.
        advanceSeconds(600);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT)).isEmpty();
        advanceSeconds(601);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT)).isPresent();
    }

    @Test
    void 결제_차단은_시간이_지난다고_스스로_풀리지_않는다() {
        openImmediately(AiAvailability.Capability.SUMMARY, AiProviderFailureKind.QUOTA);
        advanceSeconds(QUOTA_BLOCK_SECONDS + 1);

        assertThatThrownBy(() -> circuitBreaker.requireNewWorkAllowed(AiAvailability.Capability.SUMMARY))
                .as("확인 시각이 됐다는 것은 시험해 볼 때라는 뜻뿐이다")
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThat(circuitBreaker.isProcessingAllowed(AiAvailability.Capability.SUMMARY)).isFalse();

        circuitBreaker.recordProbeSuccess(
                circuitBreaker.admitProbe(AiAvailability.Capability.SUMMARY).orElseThrow());

        assertThat(circuitBreaker.isProcessingAllowed(AiAvailability.Capability.SUMMARY))
                .as("실제 확인 호출이 성공한 뒤에야 다시 쓴다").isTrue();
    }

    // --- 확인은 전용 입구로만 -----------------------------------------------------------------------

    @Test
    void 차단_시간이_지나도_사용자_요청과_큐_워커는_통과하지_못한다() {
        // 라이브러리에 맡기면 차단 시간이 지난 뒤 마침 도착한 호출이 반쯤 열린 자리를 차지해 시험 호출이 된다.
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);

        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThatThrownBy(() -> circuitBreaker.requireNewWorkAllowed(AiAvailability.Capability.CHAT))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThat(circuitBreaker.stateOf(AiAvailability.Capability.CHAT))
                .as("일반 호출은 자리를 물어보지도 않으므로 반쯤 열린 상태로 넘어가지 않는다")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 확인이_진행_중인_동안에도_사용자_요청은_막힌다() {
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        circuitBreaker.admitProbe(AiAvailability.Capability.CHAT).orElseThrow();

        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThat(circuitBreaker.isProcessingAllowed(AiAvailability.Capability.CHAT)).isFalse();
    }

    @Test
    void 정상인_기능은_확인하지_않는다() {
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.TITLE)).isEmpty();
    }

    @Test
    void 여럿이_동시에_두드려도_확인_허가는_하나만_나간다() throws Exception {
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);

        int racers = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(racers)) {
            for (int racer = 0; racer < racers; racer++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return circuitBreaker.admitProbe(AiAvailability.Capability.CHAT).isPresent();
                }));
            }
            start.countDown();
            long granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    granted++;
                }
            }
            assertThat(granted)
                    .as("막 살아난 공급자에 확인이 한꺼번에 몰리면 안 된다")
                    .isEqualTo(1);
        }
    }

    @Test
    void 확인자가_사라지면_임차가_끝난_뒤_다음_회차가_넘겨받는다() {
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        AiProviderCircuitBreaker.ProbePermit lost =
                circuitBreaker.admitProbe(AiAvailability.Capability.CHAT).orElseThrow();

        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT))
                .as("임차가 살아 있는 동안은 아무도 넘겨받지 못한다")
                .isEmpty();

        advanceSeconds(PROBE_LEASE_SECONDS + 1);
        AiProviderCircuitBreaker.ProbePermit current =
                circuitBreaker.admitProbe(AiAvailability.Capability.CHAT).orElseThrow();

        circuitBreaker.recordProbeSuccess(lost);
        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .as("임차를 잃은 확인의 성공으로 차단을 풀면 확인이 둘이 된 셈이다")
                .isInstanceOf(AiDependencyUnavailableException.class);

        circuitBreaker.recordProbeSuccess(current);
        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT)).doesNotThrowAnyException();
    }

    @Test
    void 확인이_실패하면_다음_확인까지의_간격이_배로_늘어난다() {
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        circuitBreaker.recordProbeFailure(
                circuitBreaker.admitProbe(AiAvailability.Capability.CHAT).orElseThrow(),
                AiProviderFailureClassifier.Classification.of(AiProviderFailureKind.TRANSIENT));

        advanceSeconds(TRANSIENT_BLOCK_SECONDS + 1);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT))
                .as("같은 간격으로 돌아가면 죽어 있는 공급자를 영영 30초마다 두드린다")
                .isEmpty();
        advanceSeconds(TRANSIENT_BLOCK_SECONDS + 1);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT)).isPresent();
    }

    @Test
    void 확인을_반납하면_차단_시간을_늘리지_않고_곧바로_다시_확인할_수_있다() {
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        AiProviderCircuitBreaker.ProbePermit permit =
                circuitBreaker.admitProbe(AiAvailability.Capability.CHAT).orElseThrow();

        circuitBreaker.releaseProbe(permit);

        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT)).isPresent();
    }

    @Test
    void 차단이_새로_열린_뒤_도착한_옛_호출의_성공은_그_차단을_닫지_못한다() {
        AiProviderCircuitBreaker.CallPermit oldCall = circuitBreaker.admit(AiAvailability.Capability.CHAT);
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);

        circuitBreaker.recordSuccess(oldCall);

        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .isInstanceOf(AiDependencyUnavailableException.class);
    }

    @Test
    void 옛_호출의_결과가_진행_중인_확인의_자리를_빼앗지_않는다() {
        // 반쯤 열린 상태의 자리는 하나뿐이다. 옛 호출이 그 자리를 반납해 버리면 확인이 자리를 잃는다.
        AiProviderCircuitBreaker.CallPermit oldCall = circuitBreaker.admit(AiAvailability.Capability.CHAT);
        openImmediately(AiAvailability.Capability.CHAT, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        AiProviderCircuitBreaker.ProbePermit probe =
                circuitBreaker.admitProbe(AiAvailability.Capability.CHAT).orElseThrow();

        circuitBreaker.recordSuccess(oldCall);
        circuitBreaker.recordFailure(
                oldCall, AiProviderFailureClassifier.Classification.of(AiProviderFailureKind.NOT_COUNTED));

        circuitBreaker.recordProbeSuccess(probe);
        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .as("확인은 자기 자리를 그대로 들고 있어야 한다")
                .doesNotThrowAnyException();
    }

    // --- 복구 뒤의 적체 처리 ---------------------------------------------------------------------

    @Test
    void 큐가_있는_기능은_복구_직후_적체만_처리하고_신규_접수는_막는다() {
        openImmediately(AiAvailability.Capability.SUMMARY, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        circuitBreaker.recordProbeSuccess(
                circuitBreaker.admitProbe(AiAvailability.Capability.SUMMARY).orElseThrow());

        assertThat(circuitBreaker.isProcessingAllowed(AiAvailability.Capability.SUMMARY))
                .as("쌓인 작업은 처리해야 한다").isTrue();
        assertThatThrownBy(() -> circuitBreaker.requireNewWorkAllowed(AiAvailability.Capability.SUMMARY))
                .isInstanceOf(AiDependencyUnavailableException.class);

        circuitBreaker.markQueueDrained(AiAvailability.Capability.SUMMARY);

        assertThatCode(() -> circuitBreaker.requireNewWorkAllowed(AiAvailability.Capability.SUMMARY))
                .doesNotThrowAnyException();
    }

    @Test
    void 검토가_복구되면_그_동안_쌓인_감상문_적체를_먼저_비운다() {
        // 검토가 막힌 동안 감상문 작업은 쌓이지만 감상문 자체 상태는 정상이다. 그대로 두면 살아나는 순간
        // 신규 접수가 적체를 앞지른다.
        openImmediately(AiAvailability.Capability.MODERATION, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);

        circuitBreaker.recordProbeSuccess(
                circuitBreaker.admitProbe(AiAvailability.Capability.MODERATION).orElseThrow());

        assertThat(circuitBreaker.isDrainPending(AiAvailability.Capability.SUMMARY)).isTrue();
        assertThatThrownBy(() -> circuitBreaker.requireNewWorkAllowed(AiAvailability.Capability.SUMMARY))
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThat(circuitBreaker.isDrainPending(AiAvailability.Capability.CONTEXT_SUMMARY))
                .as("컨텍스트 요약은 검토에 기대지 않는다 — 아무 이유 없이 접수를 막지 않는다")
                .isFalse();
    }

    @Test
    void 검토_복구가_스스로_차단된_감상문의_상태를_덮지_않는다() {
        openImmediately(AiAvailability.Capability.SUMMARY, AiProviderFailureKind.QUOTA);
        openImmediately(AiAvailability.Capability.MODERATION, AiProviderFailureKind.RATE_LIMIT);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);

        circuitBreaker.recordProbeSuccess(
                circuitBreaker.admitProbe(AiAvailability.Capability.MODERATION).orElseThrow());

        assertThat(circuitBreaker.stateOf(AiAvailability.Capability.SUMMARY))
                .as("스스로 차단된 기능은 자기 확인이 성공할 때까지 그대로다")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    // --- 도우미 ----------------------------------------------------------------------------------

    /** 한 건으로 바로 막는 종류의 실패를 한 번 기록해 차단을 연다. */
    private void openImmediately(AiAvailability.Capability capability, AiProviderFailureKind kind) {
        AiProviderCircuitBreaker.CallPermit permit = circuitBreaker.admit(capability);
        circuitBreaker.recordFailure(permit, AiProviderFailureClassifier.Classification.of(kind));
    }

    private void recordTransientFailures(AiAvailability.Capability capability, int count) {
        for (int attempt = 0; attempt < count; attempt++) {
            AiProviderCircuitBreaker.CallPermit permit = circuitBreaker.admit(capability);
            circuitBreaker.recordFailure(
                    permit, AiProviderFailureClassifier.Classification.of(AiProviderFailureKind.TRANSIENT));
        }
    }

    private void advanceSeconds(long seconds) {
        now.addAndGet(seconds * 1000L);
    }
}
