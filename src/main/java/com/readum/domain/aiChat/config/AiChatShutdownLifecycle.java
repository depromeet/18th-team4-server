package com.readum.domain.aiChat.config;

import com.readum.domain.aiChat.service.AiChatInFlightTurnRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 배포·재시작 때 진행 중인 채팅 턴을 기한 안에서 마무리하는 종료 절차.
 *
 * <p>순서는 <b>신규 수락 차단 → 진행 목록이 비거나 기한이 될 때까지 대기 → 실행기 종료</b> 다.
 * 기한 안에 끝나면 기한을 다 쓰지 않고 곧바로 다음 단계로 넘어간다.
 *
 * <h2>왜 웹 서버 graceful shutdown 과 같은 단계(phase)인가</h2>
 * "웹 서버와 같은 단계면 안전하다" 가 아니라, <b>이 서버의 실제 종료 유예 시간에 들어맞는 배치가 이것뿐</b>이라서다.
 * 확인한 값들(2026-09-06 기준):
 * <ul>
 *   <li>spring-boot-web-server 4.0.5 의 {@code WebServerGracefulShutdownLifecycle} 단계 = {@code Integer.MAX_VALUE - 1024}
 *       (= 2147482623), 그 뒤에 도는 servlet {@code WebServerStartStopLifecycle} 단계 = {@code Integer.MAX_VALUE - 2048}. 단계가 큰 쪽부터 멈춘다.</li>
 *   <li>spring-context 7.0.6 의 {@code DefaultLifecycleProcessor.LifecycleGroup#stop()} 은 <b>같은 단계의 빈들에게 먼저 모두
 *       stop 을 걸고 나서 하나의 latch 를 단계별 기한만큼 기다린다</b>. 같은 단계에 두면 두 대기가 겹쳐서 흐르고 기한도 공유한다.</li>
 *   <li>이 서버 설정: {@code server.shutdown=graceful}, {@code spring.lifecycle.timeout-per-shutdown-phase=60s},
 *       ai-chat 종료 대기 상한 60초, systemd {@code TimeoutStopSec=75}(그 뒤 SIGKILL).</li>
 * </ul>
 * 우리 대기를 웹 서버보다 낮은 단계에 두면 HTTP 대기(최대 60초)가 <b>끝난 뒤에</b> 우리 대기(최대 60초)가 시작돼
 * 합이 120초가 되고, 75초에 SIGKILL 이 떨어져 둘 다 잘린다. 같은 단계면 두 대기가 겹쳐 최대 60초에 들어온다.
 * SSE 는 채팅에서 가장 긴 요청이라(전달 기한 150초) HTTP 대기가 60초를 다 쓰는 일이 드물지 않으므로 이 차이는 실제다.
 *
 * <p><b>남은 위험(측정·조정 대상):</b> 우리 종료 대기 상한(60초)이 단계별 기한(60초)과 같다. 진행 중인 턴이 기한을
 * 끝까지 쓰면 두 시계가 같은 순간에 만료돼 lifecycle 쪽에 "기한 내 종료 실패" 경고가 남을 수 있다.
 * 값 조정(종료 대기 &lt; 단계별 기한)은 부하 측정 뒤에 함께 정한다.
 *
 * <h2>실행기·HTTP 클라이언트·DB 자원의 종료 순서</h2>
 * 실행기 두 개는 이 클래스가 직접 닫는다(그래서 {@code AiChatExecutorConfig} 에서 소멸 메서드를 껐다).
 * DB 커넥션 풀(HikariCP)과 HTTP 클라이언트는 <b>빈 소멸</b> 때 닫히는데, 빈 소멸은 모든 SmartLifecycle 종료가
 * 끝난 다음이다({@code AbstractApplicationContext#doClose} 는 {@code lifecycleProcessor.onClose()} 뒤에
 * {@code destroyBeans()} 를 부른다). 그래서 <b>후처리가 DB 를 쓰는 동안 풀이 먼저 닫히지는 않는다</b>.
 * 다만 단계별 기한을 넘겨 lifecycleProcessor 가 우리를 두고 먼저 진행한 경우에는 이 보장이 사라진다 —
 * 그때 실패한 후처리는 DB 의 미종료 예약으로 남아 미정산 예약 반환 대상이 된다.
 */
@Slf4j
@Component
public class AiChatShutdownLifecycle implements SmartLifecycle {

    /**
     * 웹 서버 graceful shutdown 과 같은 단계. spring-boot-web-server 4.0.5 의
     * {@code WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE} 와 같은 값이지만, 그 상수가
     * 제거 예정(deprecated for removal)으로 표시돼 있어 값을 직접 적는다. 실제 빈의 단계와 같은지는
     * 컨텍스트를 띄우는 통합 테스트가 확인하므로, Boot 가 값을 바꾸면 빌드에서 드러난다.
     */
    static final int WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE = Integer.MAX_VALUE - 1024;

    private final AiChatInFlightTurnRegistry inFlightTurnRegistry;
    private final ExecutorService aiChatDeliveryExecutor;
    private final ExecutorService aiChatPostProcessingExecutor;
    private final Duration shutdownWait;

    private volatile boolean running = false;

    public AiChatShutdownLifecycle(
            AiChatInFlightTurnRegistry inFlightTurnRegistry,
            ExecutorService aiChatDeliveryExecutor,
            ExecutorService aiChatPostProcessingExecutor,
            AiChatProperties aiChatProperties
    ) {
        this.inFlightTurnRegistry = inFlightTurnRegistry;
        this.aiChatDeliveryExecutor = aiChatDeliveryExecutor;
        this.aiChatPostProcessingExecutor = aiChatPostProcessingExecutor;
        this.shutdownWait = Duration.ofSeconds(aiChatProperties.streaming().shutdownWaitSeconds());
    }

    @Override
    public int getPhase() {
        return WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 종료 절차를 별도 스레드에서 진행하고 끝나면 콜백을 부른다.
     * lifecycleProcessor 스레드에서 그대로 기다리면 같은 단계의 다른 종료(웹 서버 graceful shutdown)가
     * 우리 대기 뒤로 밀려 겹치지 못한다.
     */
    @Override
    public void stop(Runnable callback) {
        running = false;
        Thread shutdownThread = Thread.ofPlatform()
                .name("ai-chat-shutdown")
                .unstarted(() -> {
                    try {
                        drainAndShutdown();
                    } finally {
                        callback.run();
                    }
                });
        shutdownThread.start();
    }

    /** 직접 {@code stop()} 을 부른 경우(테스트·수동 호출)에도 같은 절차를 밟는다. 이 경로는 부른 스레드에서 그대로 기다린다. */
    @Override
    public void stop() {
        running = false;
        drainAndShutdown();
    }

    private void drainAndShutdown() {
        Instant deadline = Instant.now().plus(shutdownWait);

        // (1) 신규 수락 차단. 이 호출이 돌아온 뒤 시작된 등록은 거절되고, 그 전에 수락된 턴은 모두 목록에 있다.
        int inFlightAtBlock = inFlightTurnRegistry.blockNewTurns();
        log.info("AI 채팅 종료 절차 시작 — 신규 턴 수락 차단, 진행 중 {}건, 최대 대기 {}초",
                inFlightAtBlock, shutdownWait.toSeconds());

        // (2) 진행 목록이 빌 때까지 대기. 전달(SSE)의 종료는 조건이 아니다.
        boolean drained = awaitInFlightTurns();

        if (drained) {
            log.info("AI 채팅 진행 턴 정리 완료 — 남은 턴 없음");
        } else {
            List<AiChatInFlightTurnRegistry.InFlightTurn> remaining = inFlightTurnRegistry.snapshot();
            // 여기서 강제로 끊지 않는다. 남은 턴의 예약은 DB 미종료 요청 기록을 보고 반환하는 쪽이 되돌린다.
            log.warn("AI 채팅 종료 대기 기한({}초) 초과 — 남은 턴 {}건은 DB 의 미정산 예약 반환에 맡긴다: {}",
                    shutdownWait.toSeconds(), remaining.size(), describe(remaining));
        }

        // (3) 실행기 종료.
        shutdownExecutors(deadline);
    }

    private boolean awaitInFlightTurns() {
        try {
            return inFlightTurnRegistry.awaitAllTurnsFinished(shutdownWait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("AI 채팅 종료 대기가 중단됐다 — 남은 턴은 DB 의 미정산 예약 반환에 맡긴다");
            return false;
        }
    }

    /**
     * 후처리 실행기는 남은 기한만큼 마무리를 기다리고, 전달 실행기는 기다리지 않는다(전달은 종료 조건이 아니다).
     * 어느 쪽도 {@code shutdownNow()} 로 끊지 않는다 — 진행 중인 DB 트랜잭션을 interrupt 로 끊으면
     * 커밋 여부를 알 수 없는 상태가 늘어날 뿐이다.
     */
    private void shutdownExecutors(Instant deadline) {
        aiChatPostProcessingExecutor.shutdown();
        aiChatDeliveryExecutor.shutdown();

        long remainingMillis = Math.max(0L, Duration.between(Instant.now(), deadline).toMillis());
        try {
            if (!aiChatPostProcessingExecutor.awaitTermination(remainingMillis, TimeUnit.MILLISECONDS)) {
                log.warn("AI 채팅 후처리 실행기가 기한 안에 끝나지 않았다 — 미완료 후처리는 DB 의 미정산 예약 반환 대상이다");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("AI 채팅 후처리 실행기 종료 대기가 중단됐다");
        }
        log.info("AI 채팅 실행기 종료 완료 — 이후 제출은 거절된다");
    }

    private String describe(List<AiChatInFlightTurnRegistry.InFlightTurn> remaining) {
        Instant now = Instant.now();
        return remaining.stream()
                .map(turn -> "requestId=%s sessionId=%s userId=%s 경과=%d초".formatted(
                        turn.requestId(),
                        turn.sessionId(),
                        turn.userId(),
                        Duration.between(turn.startedAt(), now).toSeconds()))
                .toList()
                .toString();
    }
}
