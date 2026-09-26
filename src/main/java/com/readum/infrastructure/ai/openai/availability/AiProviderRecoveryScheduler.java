package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 차단된 기능의 복구를 <b>이 스케줄러만</b> 확인한다.
 *
 * <p><b>왜 사용자 요청과 큐 작업에서 떼어 냈는가.</b> 예전에는 차단 시간이 지나면 마침 도착한 요청 하나가
 * 복구 확인을 겸했다. 그 요청은 아직 고쳐졌는지 모르는 공급자를 향해 나가므로 사용자를 기다리게 하고,
 * 큐 작업이 겸하면 빈 큐에서는 아무도 시험하지 않아 차단이 영영 풀리지 않는 교착이 생겼다. 그 교착을 풀려고
 * "빈 큐일 때 신규 접수 한 건만 받는다" 같은 예외가 붙었고, 그 예외가 다시 "비었다는 것을 언제 어떻게
 * 관찰하나" 라는 표식을 불렀다. 확인을 따로 떼면 그 사슬이 전부 사라진다 — 사용자 요청과 큐 작업은
 * 차단 중 <b>예외 없이</b> 막히고, 확인은 사용자 트래픽이 하나도 없어도 주기마다 일어난다.
 *
 * <p><b>확인은 이 서버에서 한 번에 하나다.</b> 임차를 하나만 내주고, 확인자가 죽어 임차가 만료되면
 * 다음 회차가 넘겨받으며, 늦게 돌아온 옛 확인의 결과는 세대·소유자 펜싱이 버린다.
 * 차단 상태가 서버마다 따로이므로 <b>여러 서버가 뜨면 확인도 서버마다 하나씩</b> 나간다 —
 * 그 한계는 {@code AiProviderCircuitBreaker} 에 적어 두었다.
 *
 * <p><b>유계 동시성.</b> {@code @Scheduled} 메서드는 제출만 하고 곧 돌아온다. 실행은 전용 단일 스레드이고,
 * 앞 회차가 아직 돌고 있으면 이번 회차를 건너뛴다. 그래서 한 인스턴스의 동시 확인 수는 1 이고,
 * 인스턴스 사이에서도 기능마다 1 이다.
 *
 * <p>여기서는 사용자 예산·채팅 DB·작업 큐를 건드리지 않는다. 하는 일은 확인 호출과 상태 기록뿐이다.
 */
@Slf4j
@Component
public class AiProviderRecoveryScheduler {

    private final AiProviderCircuitBreaker circuitBreaker;
    private final AiProviderFailureClassifier failureClassifier;
    private final List<AiProviderRecoveryProbe> probes;
    private final ExecutorService probeExecutor;

    /** 앞 회차가 아직 돌고 있는지 — 참이면 이번 회차는 제출하지 않는다. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AiProviderRecoveryScheduler(
            AiProviderCircuitBreaker circuitBreaker,
            AiProviderFailureClassifier failureClassifier,
            List<AiProviderRecoveryProbe> probes,
            @Qualifier("aiRecoveryProbeExecutor") ExecutorService probeExecutor
    ) {
        this.circuitBreaker = circuitBreaker;
        this.failureClassifier = failureClassifier;
        this.probes = probes;
        this.probeExecutor = probeExecutor;
    }

    @Scheduled(fixedDelayString = "${openai.availability.probe.scan-interval-ms}")
    public void scheduleRecoveryProbes() {
        if (!running.compareAndSet(false, true)) {
            log.debug("AI 공급자 복구 확인 회차 건너뜀 — 앞 회차가 아직 돌고 있다");
            return;
        }
        try {
            probeExecutor.execute(this::runDueProbes);
        } catch (RejectedExecutionException executorClosed) {
            running.set(false);
            log.warn("AI 공급자 복구 확인 제출 거절 — 종료 중으로 보인다", executorClosed);
        }
    }

    /**
     * 기한이 된 기능만 확인한다. 순차로 도는 이유는 동시 확인 수를 1 로 묶기 위해서다 —
     * 다섯 기능이 동시에 막혀 있어도 확인이 한꺼번에 나가지 않는다.
     */
    private void runDueProbes() {
        try {
            for (AiProviderRecoveryProbe probe : probes) {
                probeIfDue(probe);
            }
        } finally {
            running.set(false);
        }
    }

    private void probeIfDue(AiProviderRecoveryProbe probe) {
        AiAvailability.Capability capability = probe.capability();
        // 정상·적체 처리 상태이거나, 아직 확인 시각이 아니거나, 다른 인스턴스가 확인 중이면 빈 값이다.
        // 그 경우 외부 호출은 일어나지 않는다 — 정상 기능을 계속 두드리지 않는다.
        Optional<AiProviderCircuitBreaker.ProbePermit> permit = circuitBreaker.admitProbe(capability);
        if (permit.isEmpty()) {
            return;
        }
        AiProviderCircuitBreaker.ProbePermit granted = permit.get();
        AiProviderRecoveryProbe.ProbeOutcome outcome;
        try {
            outcome = probe.probe();
        } catch (RuntimeException unexpected) {
            // 확인 구현이 값 대신 예외를 냈다 — 우리 쪽 버그다. 차단 시간을 늘리지 않고 임차만 돌려준다.
            log.error("AI 공급자 복구 확인이 예외로 끝났다 — 임차만 반납한다 capability={}", capability, unexpected);
            circuitBreaker.releaseProbe(granted);
            return;
        }
        switch (outcome) {
            case AiProviderRecoveryProbe.ProbeOutcome.Verified ignored -> {
                circuitBreaker.recordProbeSuccess(granted);
                log.info("AI 공급자 복구 확인 성공 capability={} 큐={}",
                        capability, capability.queued() ? "적체 처리부터" : "없음");
            }
            case AiProviderRecoveryProbe.ProbeOutcome.Failed failed -> {
                AiProviderFailureClassifier.Classification classification =
                        failureClassifier.classify(failed.cause());
                if (!classification.kind().counted()) {
                    // 그 요청 하나의 입력 문제이거나 우리 쪽 오류다 — 공급자 상태로 세지 않고 반납한다.
                    circuitBreaker.releaseProbe(granted);
                    log.warn("AI 공급자 복구 확인 판정 보류 capability={} 종류={} 원인={}",
                            capability, classification.kind(), failed.cause().toString());
                    return;
                }
                circuitBreaker.recordProbeFailure(granted, classification);
                log.info("AI 공급자 복구 확인 실패 — 다시 차단한다 capability={} 종류={}",
                        capability, classification.kind());
            }
        }
    }
}
