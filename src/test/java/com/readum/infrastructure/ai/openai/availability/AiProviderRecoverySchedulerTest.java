package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 확인 결과가 상태에 어떻게 반영되는지 — 세 결과(확인됨 · 미룸 · 실패)가 각각 다르게 기록돼야 한다.
 * 잘못 묶으면 차단 시간이 공급자와 무관한 이유로 늘어나거나, 죽은 공급자가 살아난 것으로 판정된다.
 *
 * <p>실행기는 같은 스레드에서 곧바로 돌리는 것으로 바꿔 끼워, 제출 시점과 실행 시점을 나누지 않고 본다.
 */
class AiProviderRecoverySchedulerTest {

    private static final AiAvailability.Capability CAPABILITY = AiAvailability.Capability.CHAT;

    private final AiProviderCircuitBreaker circuitBreaker = mock(AiProviderCircuitBreaker.class);
    private final AiProviderFailureClassifier failureClassifier = new AiProviderFailureClassifier();

    private AiProviderCircuitBreaker.ProbePermit permit() {
        return new AiProviderCircuitBreaker.ProbePermit(CAPABILITY, 7L, "owner-1", System.nanoTime());
    }

    private AiProviderRecoveryScheduler scheduler(AiProviderRecoveryProbe probe) {
        return new AiProviderRecoveryScheduler(
                circuitBreaker, failureClassifier, List.of(probe), sameThreadExecutor());
    }

    /** 제출을 곧바로 실행하는 실행기 — 스케줄러의 판정만 보기 위해 시간 축을 없앤다. */
    private java.util.concurrent.ExecutorService sameThreadExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
            @Override public void execute(Runnable command) {
                command.run();
            }
        };
    }

    private static AiProviderRecoveryProbe probeReturning(AiProviderRecoveryProbe.ProbeOutcome outcome) {
        return new AiProviderRecoveryProbe() {
            @Override public AiAvailability.Capability capability() {
                return CAPABILITY;
            }
            @Override public ProbeOutcome probe() {
                return outcome;
            }
        };
    }

    @Test
    void 확인할_때가_아니면_외부_호출을_하지_않는다() {
        // 정상 기능을 주기마다 두드리면 살아 있는 프로젝트의 한도와 비용을 까닭 없이 쓴다.
        given(circuitBreaker.admitProbe(CAPABILITY)).willReturn(Optional.empty());
        List<String> calls = new ArrayList<>();
        AiProviderRecoveryProbe probe = new AiProviderRecoveryProbe() {
            @Override public AiAvailability.Capability capability() {
                return CAPABILITY;
            }
            @Override public ProbeOutcome probe() {
                calls.add("probe");
                return new ProbeOutcome.Verified();
            }
        };

        scheduler(probe).scheduleRecoveryProbes();

        assertThat(calls).isEmpty();
        verify(circuitBreaker, never()).recordProbeSuccess(any());
        verify(circuitBreaker, never()).releaseProbe(any());
    }

    @Test
    void 유효한_판정까지_확인했으면_성공으로_기록한다() {
        AiProviderCircuitBreaker.ProbePermit permit = permit();
        given(circuitBreaker.admitProbe(CAPABILITY)).willReturn(Optional.of(permit));

        scheduler(probeReturning(new AiProviderRecoveryProbe.ProbeOutcome.Verified()))
                .scheduleRecoveryProbes();

        verify(circuitBreaker).recordProbeSuccess(permit);
    }

    @Test
    void 확인이_성공하면_그_결과를_차단기에_남긴다() {
        AiProviderCircuitBreaker.ProbePermit permit = permit();
        given(circuitBreaker.admitProbe(CAPABILITY)).willReturn(Optional.of(permit));

        scheduler(probeReturning(new AiProviderRecoveryProbe.ProbeOutcome.Verified()))
                .scheduleRecoveryProbes();

        verify(circuitBreaker).recordProbeSuccess(permit);
    }

    @Test
    void 공급자_실패로_끝났으면_분류한_뒤_실패로_기록한다() {
        AiProviderCircuitBreaker.ProbePermit permit = permit();
        given(circuitBreaker.admitProbe(CAPABILITY)).willReturn(Optional.of(permit));

        scheduler(probeReturning(new AiProviderRecoveryProbe.ProbeOutcome.Failed(
                new TransientAiException("OpenAI 503")))).scheduleRecoveryProbes();

        org.mockito.ArgumentCaptor<AiProviderFailureClassifier.Classification> recorded =
                org.mockito.ArgumentCaptor.forClass(AiProviderFailureClassifier.Classification.class);
        verify(circuitBreaker).recordProbeFailure(org.mockito.ArgumentMatchers.eq(permit), recorded.capture());
        assertThat(recorded.getValue().kind()).isEqualTo(AiProviderFailureKind.TRANSIENT);
    }

    @Test
    void 세지_않는_실패로_끝났으면_판정을_보류하고_임차만_돌려준다() {
        // 그 요청 하나의 입력 문제나 우리 쪽 오류로 차단 시간을 늘리면, 공급자가 살아 있어도
        // 확인 간격이 계속 뒤로 밀린다.
        AiProviderCircuitBreaker.ProbePermit permit = permit();
        given(circuitBreaker.admitProbe(CAPABILITY)).willReturn(Optional.of(permit));

        scheduler(probeReturning(new AiProviderRecoveryProbe.ProbeOutcome.Failed(
                new IllegalStateException("우리 쪽 버그")))).scheduleRecoveryProbes();

        verify(circuitBreaker).releaseProbe(permit);
        verify(circuitBreaker, never()).recordProbeFailure(any(), any());
    }

    @Test
    void 확인이_값_대신_예외를_내도_임차만_돌려주고_넘어간다() {
        AiProviderCircuitBreaker.ProbePermit permit = permit();
        given(circuitBreaker.admitProbe(CAPABILITY)).willReturn(Optional.of(permit));
        AiProviderRecoveryProbe broken = new AiProviderRecoveryProbe() {
            @Override public AiAvailability.Capability capability() {
                return CAPABILITY;
            }
            @Override public ProbeOutcome probe() {
                throw new IllegalStateException("확인 구현 버그");
            }
        };

        scheduler(broken).scheduleRecoveryProbes();

        verify(circuitBreaker).releaseProbe(permit);
        verify(circuitBreaker, never()).recordProbeFailure(any(), any());
    }

    @Test
    void 앞_회차가_아직_돌고_있으면_이번_회차는_제출하지_않는다() throws Exception {
        // 동시 확인 수를 1 로 묶는 장치다 — 확인이 응답을 기다리는 동안 회차가 겹쳐 쌓이면
        // 차단된 프로젝트에 확인이 한꺼번에 몰린다.
        AiProviderCircuitBreaker.ProbePermit permit = permit();
        given(circuitBreaker.admitProbe(CAPABILITY)).willReturn(Optional.of(permit));
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AiProviderRecoveryProbe slow = new AiProviderRecoveryProbe() {
            @Override public AiAvailability.Capability capability() {
                return CAPABILITY;
            }
            @Override public ProbeOutcome probe() {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new ProbeOutcome.Verified();
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            AiProviderRecoveryScheduler scheduler = new AiProviderRecoveryScheduler(
                    circuitBreaker, failureClassifier, List.of(slow), pool);

            scheduler.scheduleRecoveryProbes();
            assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            scheduler.scheduleRecoveryProbes(); // 앞 회차가 아직 매달려 있다 — 건너뛰어야 한다
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        verify(circuitBreaker).admitProbe(CAPABILITY);
        verify(circuitBreaker).recordProbeSuccess(permit);
    }
}
