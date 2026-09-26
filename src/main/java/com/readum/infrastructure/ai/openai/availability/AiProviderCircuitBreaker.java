package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 기능별 공급자 장애 차단기. 상태 기계와 실패율 집계는 Resilience4j 가 소유한다 —
 * 기능마다 {@link CircuitBreaker} 하나씩이고, 같은 것을 우리가 다시 세지 않는다.
 * 왜 Redis 공유 상태에서 이리로 옮겼는지는 {@code docs/domain/ai-chat.md} 의 "공급자 장애 차단" 절에 있다.
 *
 * <p>불변식
 * <ol>
 *   <li>차단은 <b>이 서버 안에서만</b> 유효하다. 서버마다 따로 막히고, 복구 확인도 서버마다 하나이며,
 *       프로세스를 다시 띄우면 정상에서 시작한다.</li>
 *   <li>일반 호출은 정상이 아닌 상태에서 <b>자리를 물어보지도 않는다</b> — 물어보면 라이브러리가 시간을 보고
 *       반쯤 열어, 그 사용자 호출이 시험 호출이 되어 버린다. 반쯤 여는 것은 {@link #admitProbe} 뿐이다.</li>
 *   <li>반쯤 열린 상태 자체는 건강 확인이 아니다. <b>실제 확인 호출이 성공해야</b> 닫힌다 —
 *       결제·인증 차단이 시간만으로 풀리지 않는 근거다.</li>
 *   <li>라이브러리 위에 더한 상태는 셋뿐이다: 다시 두드릴 시각, 복구 확인의 임차·세대, 적체 우선 표식.
 *       어느 것도 라이브러리의 상태를 흉내 내지 않는다.</li>
 *   <li>{@link CapabilityCircuit} 의 필드는 모두 그 잠금 아래에서만 읽고 쓴다. 잠금 안에서는 입출력을 하지 않는다.</li>
 * </ol>
 */
@Slf4j
@Component
public class AiProviderCircuitBreaker {

    private final AiAvailabilityProperties properties;
    private final Map<AiAvailability.Capability, CapabilityCircuit> circuits =
            new EnumMap<>(AiAvailability.Capability.class);

    /** 차단 시각 계산에 쓰는 시계(epoch millis). 테스트가 잠들지 않고 시간을 흘릴 수 있게 바꿔 끼운다. */
    private final LongSupplier clock;

    @Autowired
    public AiProviderCircuitBreaker(AiAvailabilityProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    AiProviderCircuitBreaker(AiAvailabilityProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(circuitBreakerConfig(properties));
        for (AiAvailability.Capability capability : AiAvailability.Capability.values()) {
            circuits.put(capability, new CapabilityCircuit(
                    capability, registry.circuitBreaker(capability.name().toLowerCase())));
        }
    }

    /**
     * 다섯 기능이 같은 값을 쓴다. 시간 기준 창을 쓰는 이유: 호출 수 기준 창은 호출이 뜸한 기능에서
     * 몇 시간 전 실패가 창에 남아 지금 판정에 끼어든다.
     * 반쯤 열린 상태의 자리를 1 로 둔 것이 "확인은 한 번에 하나" 의 마지막 방어다.
     */
    private static CircuitBreakerConfig circuitBreakerConfig(AiAvailabilityProperties properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize((int) properties.window().toSeconds())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRatePercent())
                .permittedNumberOfCallsInHalfOpenState(1)
                // 시간이 지났다는 것만으로 반쯤 열지 않는다 — 여는 것은 전용 확인뿐이다.
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                // 반쯤 열린 상태에 기한을 두지 않는다. 확인이 매달려도 우리 임차 기한이 다음 회차에 인계한다.
                .maxWaitDurationInHalfOpenState(Duration.ZERO)
                .writableStackTraceEnabled(false)
                .build();
    }

    /**
     * 일반 호출의 허가. 결과를 남길 때 그대로 돌려줘야 한다 —
     * {@code generation} 은 "이 호출이 나갈 때의 차단 세대" 로, 그 사이 차단이 새로 열렸는지를 가른다.
     */
    public record CallPermit(AiAvailability.Capability capability, long generation, long startNanos) {
    }

    /** 복구 확인의 허가. 전용 스케줄러만 받는다 — 같은 기능에 대해 이 서버에서 한 번에 하나만 존재한다. */
    public record ProbePermit(
            AiAvailability.Capability capability, long generation, String owner, long startNanos) {
    }

    /**
     * 이 기능으로 지금 나가도 되는지 확인하고 허가를 받는다. 의존 기능까지 함께 본다.
     *
     * @throws AiDependencyUnavailableException 차단 중일 때(남은 시간과 무관하게 막는다)
     */
    public CallPermit admit(AiAvailability.Capability capability) {
        requireDependenciesUsable(capability);
        CapabilityCircuit circuit = circuit(capability);
        synchronized (circuit.lock) {
            // 불변식 2: 정상이 아니면 자리를 물어보지도 않는다.
            if (circuit.breaker.getState() != CircuitBreaker.State.CLOSED
                    || !circuit.breaker.tryAcquirePermission()) {
                throw blocked(capability, capability, circuit.remainingWaitMillis(clock.getAsLong()));
            }
            return new CallPermit(capability, circuit.generation, System.nanoTime());
        }
    }

    /**
     * 복구 확인을 해도 되는지 묻고 임차를 받는다 — <b>전용 스케줄러만</b> 부른다.
     * 빈 값은 셋 중 하나다: 이미 정상, 아직 확인 시각 전, 앞선 확인의 임차가 살아 있음.
     * 의존 기능은 보지 않는다 — 각 확인은 자기 프로젝트만 두드리므로 두 기능의 복구가 서로를 기다릴 이유가 없다.
     */
    public Optional<ProbePermit> admitProbe(AiAvailability.Capability capability) {
        CapabilityCircuit circuit = circuit(capability);
        synchronized (circuit.lock) {
            CircuitBreaker.State state = circuit.breaker.getState();
            if (state == CircuitBreaker.State.CLOSED) {
                // 정상 기능을 주기마다 두드리지 않는다.
                return Optional.empty();
            }
            long now = clock.getAsLong();
            if (state == CircuitBreaker.State.HALF_OPEN) {
                if (now < circuit.probeExpiresAtMillis) {
                    return Optional.empty();
                }
                // 앞선 확인자가 결과를 돌려주지 못했다(죽었다) — 자리를 되돌려 인계한다.
                // 세대를 올려, 그 확인이 뒤늦게 살아 돌아와도 지금 상태를 바꾸지 못하게 한다.
                circuit.breaker.transitionToOpenState();
                circuit.generation++;
                circuit.clearProbe();
                log.warn("AI 공급자 복구 확인 임차 만료 — 다음 회차가 넘겨받는다 capability={}", capability);
            }
            if (now < circuit.retryDeadlineMillis) {
                return Optional.empty();
            }
            circuit.breaker.transitionToHalfOpenState();
            if (!circuit.breaker.tryAcquirePermission()) {
                // 자리를 얻지 못했다 — 원래대로 돌려 놓는다(다음 회차가 다시 시도한다).
                circuit.breaker.transitionToOpenState();
                return Optional.empty();
            }
            circuit.probeOwner = UUID.randomUUID().toString();
            circuit.probeExpiresAtMillis = now + properties.probeLease().toMillis();
            log.info("AI 공급자 복구 확인 시작 capability={} 세대={}", capability, circuit.generation);
            return Optional.of(new ProbePermit(
                    capability, circuit.generation, circuit.probeOwner, System.nanoTime()));
        }
    }

    /** 일반 호출이 정상으로 끝났다 — 관찰 창의 표본으로 센다. */
    public void recordSuccess(CallPermit permit) {
        CapabilityCircuit circuit = circuit(permit.capability());
        synchronized (circuit.lock) {
            if (permit.generation() != circuit.generation) {
                // 오래 매달렸다 돌아온 호출이다. <b>자리도 돌려주지 않는다</b> — 그 사이 차단이 다시 열리고
                // 복구 확인이 시작됐다면, 여기서 돌려주는 자리는 그 확인이 쥔 하나뿐인 자리다.
                return;
            }
            circuit.breaker.onSuccess(System.nanoTime() - permit.startNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** 일반 호출이 실패로 끝났다. 세지 않는 종류는 표본에도 넣지 않는다 — 공급자의 응답이 아니기 때문이다. */
    public void recordFailure(CallPermit permit, AiProviderFailureClassifier.Classification classification) {
        CapabilityCircuit circuit = circuit(permit.capability());
        synchronized (circuit.lock) {
            if (permit.generation() != circuit.generation) {
                // 옛 세대의 결과다 — 지금 상태를 바꾸지도, 지금 자리를 돌려주지도 않는다.
                return;
            }
            if (!classification.kind().counted()) {
                // 그 요청 하나의 문제이거나 우리 쪽 오류다 — 표본으로 세지 않고 자리만 돌려준다.
                circuit.breaker.releasePermission();
                return;
            }
            circuit.breaker.onError(
                    System.nanoTime() - permit.startNanos(), TimeUnit.NANOSECONDS,
                    new ProviderFailureSignal(permit.capability(), classification.kind()));
            openIfNeeded(circuit, classification, classification.kind().immediate());
        }
    }

    /** 복구 확인이 성공했다 — 큐가 있는 기능은 쌓인 작업을 먼저 비우고, 없는 기능은 곧바로 다시 받는다. */
    public void recordProbeSuccess(ProbePermit permit) {
        CapabilityCircuit circuit = circuit(permit.capability());
        synchronized (circuit.lock) {
            if (isStaleProbe(circuit, permit)) {
                return;
            }
            circuit.breaker.onSuccess(System.nanoTime() - permit.startNanos(), TimeUnit.NANOSECONDS);
            circuit.clearProbe();
            circuit.retryDeadlineMillis = 0L;
            circuit.selfBlockMillis = 0L;
            markDrainPendingAfterRecovery(permit.capability());
            log.info("AI 공급자 복구 확인 성공 capability={} 적체={}",
                    permit.capability(), permit.capability().queued() ? "먼저 비운다" : "없음");
        }
    }

    /** 복구 확인이 실패했다 — 다시 차단하고 다음 확인까지의 간격을 배로 늘린다. */
    public void recordProbeFailure(
            ProbePermit permit, AiProviderFailureClassifier.Classification classification) {
        CapabilityCircuit circuit = circuit(permit.capability());
        synchronized (circuit.lock) {
            if (isStaleProbe(circuit, permit)) {
                return;
            }
            circuit.breaker.onError(
                    System.nanoTime() - permit.startNanos(), TimeUnit.NANOSECONDS,
                    new ProviderFailureSignal(permit.capability(), classification.kind()));
            openIfNeeded(circuit, classification, true);
            log.info("AI 공급자 복구 확인 실패 — 다시 차단한다 capability={} 종류={}",
                    permit.capability(), classification.kind());
        }
    }

    /**
     * 복구 확인의 임차만 돌려준다 — 실패로 세지 않아 차단 시간도 늘리지 않는다.
     * 확인이 값 대신 예외로 끝났거나 공급자 실패로 셀 수 없는 오류였던 경우에 쓴다.
     */
    public void releaseProbe(ProbePermit permit) {
        CapabilityCircuit circuit = circuit(permit.capability());
        synchronized (circuit.lock) {
            if (isStaleProbe(circuit, permit)) {
                return;
            }
            circuit.breaker.releasePermission();
            if (circuit.breaker.getState() == CircuitBreaker.State.HALF_OPEN) {
                circuit.breaker.transitionToOpenState();
            }
            circuit.clearProbe();
            // 차단 시간을 늘리지 않는다 — 곧바로 다음 회차가 다시 확인할 수 있게 둔다.
            circuit.retryDeadlineMillis = clock.getAsLong();
        }
    }

    /**
     * 신규 접수를 받아도 되는지 확인한다. 막혀 있으면 던진다. 의존 기능도 함께 본다.
     * 복구 직후 아직 적체를 비우지 못한 큐도 막는다 — 그때 받아야 할 것은 이미 쌓인 작업뿐이다.
     */
    public void requireNewWorkAllowed(AiAvailability.Capability capability) {
        requireDependenciesUsable(capability);
        CapabilityCircuit circuit = circuit(capability);
        synchronized (circuit.lock) {
            if (circuit.breaker.getState() != CircuitBreaker.State.CLOSED) {
                throw blocked(capability, capability, circuit.remainingWaitMillis(clock.getAsLong()));
            }
        }
        if (circuit.drainPending.get()) {
            throw blocked(capability, capability, 0L);
        }
    }

    /**
     * 큐 작업을 처리해도 되는가. 적체를 비우는 중이어도 참이다 — 그때 막아야 하는 것은 신규 접수뿐이다.
     * 의존 기능도 함께 본다.
     */
    public boolean isProcessingAllowed(AiAvailability.Capability capability) {
        for (AiAvailability.Capability dependency : capability.dependencies()) {
            if (circuit(dependency).breaker.getState() != CircuitBreaker.State.CLOSED) {
                return false;
            }
        }
        return circuit(capability).breaker.getState() == CircuitBreaker.State.CLOSED;
    }

    /** 큐의 적체를 다 비웠다 — 이 서버에서 신규 접수를 다시 받는다. */
    public void markQueueDrained(AiAvailability.Capability capability) {
        if (!capability.queued()) {
            return;
        }
        if (circuit(capability).drainPending.compareAndSet(true, false)) {
            log.info("AI 공급자 적체 처리 완료 — 신규 접수 재개 capability={}", capability);
        }
    }

    /** 시험·진단용 — 지금 이 기능의 차단기 상태. */
    CircuitBreaker.State stateOf(AiAvailability.Capability capability) {
        CapabilityCircuit circuit = circuit(capability);
        synchronized (circuit.lock) {
            return circuit.breaker.getState();
        }
    }

    /** 시험·진단용 — 이 기능이 적체를 비우는 중인가. */
    boolean isDrainPending(AiAvailability.Capability capability) {
        return circuit(capability).drainPending.get();
    }

    private void requireDependenciesUsable(AiAvailability.Capability capability) {
        for (AiAvailability.Capability dependency : capability.dependencies()) {
            CapabilityCircuit dependencyCircuit = circuit(dependency);
            synchronized (dependencyCircuit.lock) {
                if (dependencyCircuit.breaker.getState() != CircuitBreaker.State.CLOSED) {
                    throw blocked(capability, dependency,
                            dependencyCircuit.remainingWaitMillis(clock.getAsLong()));
                }
            }
        }
    }

    /**
     * 실패를 기록한 뒤의 뒤처리. 라이브러리가 스스로 열었으면 그 사실을 받아 차단 시각을 정하고,
     * 한 건으로 바로 막아야 하는 종류(한도·결제·인증)면 여기서 열게 한다.
     * 세대는 차단이 새로 열릴 때만 오른다 — 이미 열린 뒤의 결과는 세대 펜싱에 걸려 여기 오지 않는다.
     */
    private void openIfNeeded(
            CapabilityCircuit circuit,
            AiProviderFailureClassifier.Classification classification,
            boolean forceOpen
    ) {
        boolean open = circuit.breaker.getState() == CircuitBreaker.State.OPEN;
        if (!open && forceOpen) {
            circuit.breaker.transitionToOpenState();
            open = true;
        }
        if (!open) {
            return;
        }
        long blockMillis = blockMillisFor(classification, circuit.selfBlockMillis);
        circuit.selfBlockMillis = blockMillis;
        circuit.retryDeadlineMillis = Math.max(
                circuit.retryDeadlineMillis, clock.getAsLong() + blockMillis);
        circuit.generation++;
        circuit.clearProbe();
        log.warn("AI 공급자 차단 capability={} 종류={} 다시확인까지={}초",
                circuit.capability, classification.kind(), Duration.ofMillis(blockMillis).toSeconds());
    }

    /**
     * 차단 시간. 상한({@code max-block-seconds})은 <b>우리가 스스로 늘리는 간격</b>에만 적용하고,
     * 공급자가 준 Retry-After 가 더 길면 그것이 이긴다 — 상한으로 자르면 "아직 오지 마라" 고 말한 시각 안에
     * 두드려 똑같이 거절당한다.
     *
     * @param previousBlockMillis 앞선 차단 시간. 0 이 아니면 확인이 거듭 실패한 것이라 간격을 배로 늘린다.
     */
    private long blockMillisFor(
            AiProviderFailureClassifier.Classification classification, long previousBlockMillis) {
        long blockMillis = properties.baseBlockFor(classification.kind()).toMillis();
        if (previousBlockMillis * 2 > blockMillis) {
            blockMillis = previousBlockMillis * 2;
        }
        long maxBlockMillis = properties.maxBlock().toMillis();
        if (blockMillis > maxBlockMillis) {
            blockMillis = maxBlockMillis;
        }
        Duration providerRetryAfter = classification.retryAfter();
        if (providerRetryAfter != null
                && !providerRetryAfter.isZero()
                && !providerRetryAfter.isNegative()
                && providerRetryAfter.toMillis() > blockMillis) {
            blockMillis = providerRetryAfter.toMillis();
        }
        return blockMillis;
    }

    /**
     * 복구 뒤의 적체 우선권. 큐 있는 기능은 자기 큐를, 큐가 없는 기능은 자기에게 기대는 큐를 민다 —
     * 검토가 막힌 동안 감상문 작업은 쌓이지만 감상문 자체는 정상이라, 그대로 두면 신규 접수가 적체를 앞지른다.
     */
    private void markDrainPendingAfterRecovery(AiAvailability.Capability capability) {
        if (capability.queued()) {
            circuit(capability).drainPending.set(true);
        }
        for (AiAvailability.Capability dependent : capability.queuedDependents()) {
            CapabilityCircuit dependentCircuit = circuit(dependent);
            // 스스로 차단된 큐는 건드리지 않는다 — 그 차단은 자기 확인이 풀어야 한다.
            if (dependentCircuit.breaker.getState() == CircuitBreaker.State.CLOSED) {
                dependentCircuit.drainPending.set(true);
            }
        }
    }

    /** 임차를 잃었거나 세대가 지난 확인의 결과인가 — 맞으면 아무것도 반영하지 않는다. */
    private boolean isStaleProbe(CapabilityCircuit circuit, ProbePermit permit) {
        if (permit.generation() == circuit.generation
                && permit.owner().equals(circuit.probeOwner)) {
            return false;
        }
        log.info("임차를 잃은 복구 확인의 결과를 버린다 capability={}", permit.capability());
        return true;
    }

    private CapabilityCircuit circuit(AiAvailability.Capability capability) {
        return circuits.get(capability);
    }

    private AiDependencyUnavailableException blocked(
            AiAvailability.Capability capability, AiAvailability.Capability blockedBy, long waitMillis) {
        long retryAfterSeconds = Math.max(1L, Duration.ofMillis(waitMillis).toSeconds());
        log.debug("AI 공급자 차단 중 — 호출하지 않음 capability={} 막은기능={} 남은시간={}초",
                capability, blockedBy, retryAfterSeconds);
        return new AiDependencyUnavailableException(
                AiChatErrorCode.AI_PROVIDER_UNAVAILABLE, retryAfterSeconds);
    }

    /** 기능 하나의 차단기와, 라이브러리가 모르는 우리 쪽 정보 셋(불변식 4·5). */
    private static final class CapabilityCircuit {

        private final AiAvailability.Capability capability;
        private final CircuitBreaker breaker;
        private final Object lock = new Object();

        /** 언제 다시 두드려 볼 것인가(epoch millis). 라이브러리의 대기 시간을 대신한다. */
        private long retryDeadlineMillis;

        /** 우리가 스스로 늘려 온 차단 간격 — 확인이 거듭 실패하면 배로 늘리는 밑값이다. */
        private long selfBlockMillis;

        /** 차단이 새로 열릴 때마다 1 오른다. 나갈 때의 세대와 다른 결과는 버린다. */
        private long generation;

        private String probeOwner = "";
        private long probeExpiresAtMillis;

        /** 복구 직후 아직 비우지 못한 적체가 있는가 — 신규 접수만 막는다. */
        private final AtomicBoolean drainPending = new AtomicBoolean(false);

        private CapabilityCircuit(AiAvailability.Capability capability, CircuitBreaker breaker) {
            this.capability = capability;
            this.breaker = breaker;
        }

        private void clearProbe() {
            probeOwner = "";
            probeExpiresAtMillis = 0L;
        }

        private long remainingWaitMillis(long nowMillis) {
            long waitUntil = breaker.getState() == CircuitBreaker.State.HALF_OPEN
                    ? probeExpiresAtMillis
                    : retryDeadlineMillis;
            return Math.max(0L, waitUntil - nowMillis);
        }
    }

    /** 라이브러리에 넘기는 실패 표식. 원본 예외에는 남기지 말아야 할 응답 조각이 실릴 수 있어 대신 넘긴다. */
    private static final class ProviderFailureSignal extends RuntimeException {

        private ProviderFailureSignal(AiAvailability.Capability capability, AiProviderFailureKind kind) {
            super("AI 공급자 실패 capability=" + capability + " 종류=" + kind, null, false, false);
        }
    }
}
