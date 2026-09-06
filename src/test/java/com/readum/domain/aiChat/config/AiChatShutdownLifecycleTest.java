package com.readum.domain.aiChat.config;

import com.readum.domain.aiChat.service.AiChatInFlightTurnRegistry;
import com.readum.domain.exception.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatShutdownLifecycleTest {

    private final AiChatInFlightTurnRegistry registry = new AiChatInFlightTurnRegistry();
    private final ExecutorService deliveryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService postProcessingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void 실행기를_정리한다() {
        deliveryExecutor.shutdownNow();
        postProcessingExecutor.shutdownNow();
    }

    private AiChatShutdownLifecycle lifecycle(int shutdownWaitSeconds) {
        AiChatProperties properties = new AiChatProperties(
                null,
                null,
                null,
                null,
                new AiChatProperties.Streaming(120, 30, 150, shutdownWaitSeconds, 60, 60, 256));
        AiChatShutdownLifecycle lifecycle =
                new AiChatShutdownLifecycle(registry, deliveryExecutor, postProcessingExecutor, properties);
        lifecycle.start();
        return lifecycle;
    }

    @Test
    void 웹_서버_graceful_shutdown_과_같은_단계에서_멈춘다() {
        // 순차로 두면 HTTP 대기(최대 60초) 뒤에 진행 턴 대기(최대 60초)가 시작돼 systemd 의 SIGKILL 유예(75초)를 넘긴다.
        assertThat(lifecycle(60).getPhase()).isEqualTo(Integer.MAX_VALUE - 1024);
    }

    @Test
    void 종료가_시작되면_신규_턴_수락을_차단한다() {
        AiChatShutdownLifecycle lifecycle = lifecycle(1);

        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
        assertThatThrownBy(() -> registry.register("요청-1", 10L, 100L))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 진행_중인_턴이_끝나면_기한을_다_쓰지_않고_종료_절차가_끝난다() throws Exception {
        AiChatShutdownLifecycle lifecycle = lifecycle(30);
        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register("요청-1", 10L, 100L);
        postProcessingExecutor.execute(() -> {
            sleepQuietly(Duration.ofMillis(50));
            registry.finish(turn);
        });

        CountDownLatch stopped = new CountDownLatch(1);
        long startedAt = System.nanoTime();
        lifecycle.stop(stopped::countDown);

        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(20));
        assertThat(registry.inFlightCount()).isZero();
    }

    @Test
    void 종료_대기_중에는_이미_등록된_턴의_후처리_제출을_실행기가_받는다() throws Exception {
        AiChatShutdownLifecycle lifecycle = lifecycle(30);
        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register("요청-1", 10L, 100L);

        CountDownLatch stopped = new CountDownLatch(1);
        lifecycle.stop(stopped::countDown);

        // 수락 차단이 걸린 뒤(= 종료 대기 중)에 생성이 끝나 후처리를 제출하는 상황.
        waitUntil(() -> !registry.isAcceptingNewTurns());
        CountDownLatch postProcessingRan = new CountDownLatch(1);
        postProcessingExecutor.execute(() -> {
            postProcessingRan.countDown();
            registry.finish(turn);
        });

        assertThat(postProcessingRan.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 종료_절차가_끝난_뒤의_후처리_제출은_거절돼_호출자에게_예외로_전달된다() throws Exception {
        AiChatShutdownLifecycle lifecycle = lifecycle(1);

        CountDownLatch stopped = new CountDownLatch(1);
        lifecycle.stop(stopped::countDown);
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();

        // 조용히 삼키면 저장·정산이 일어나지 않은 턴을 성공으로 기록하게 된다 — 호출자가 알 수 있게 예외로 나온다.
        assertThatThrownBy(() -> postProcessingExecutor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> deliveryExecutor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void 기한_안에_끝나지_않은_턴이_남아도_종료_절차는_끝난다() throws Exception {
        AiChatShutdownLifecycle lifecycle = lifecycle(1);
        registry.register("요청-1", 10L, 100L);

        CountDownLatch stopped = new CountDownLatch(1);
        lifecycle.stop(stopped::countDown);

        // 남은 턴을 강제로 끊지 않는다 — 예약은 DB 복구 대상으로 남긴다.
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.snapshot()).hasSize(1);
    }

    @Test
    void 전달이_끝나지_않아도_종료_절차는_전달을_기다리지_않는다() throws Exception {
        AiChatShutdownLifecycle lifecycle = lifecycle(30);
        CountDownLatch deliveryBlocked = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        deliveryExecutor.execute(() -> {
            deliveryBlocked.countDown();
            awaitQuietly(releaseDelivery);
        });
        assertThat(deliveryBlocked.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch stopped = new CountDownLatch(1);
        long startedAt = System.nanoTime();
        lifecycle.stop(stopped::countDown);

        // 진행 목록이 비어 있으므로, 전달이 아직 매달려 있어도 종료 절차는 곧바로 끝난다.
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(20));
        releaseDelivery.countDown();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("조건이 기한 안에 만족되지 않았다");
            }
            sleepQuietly(Duration.ofMillis(5));
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
