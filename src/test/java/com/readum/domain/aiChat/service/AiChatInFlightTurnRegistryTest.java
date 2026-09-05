package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatInFlightTurnRegistryTest {

    private final AiChatInFlightTurnRegistry registry = new AiChatInFlightTurnRegistry();

    @Test
    void 등록한_턴은_종료를_알릴_때까지_진행_목록에_남는다() {
        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register("요청-1", 10L, 100L);

        assertThat(registry.inFlightCount()).isEqualTo(1);
        assertThat(registry.snapshot()).containsExactly(turn);

        assertThat(registry.finish(turn)).isTrue();
        assertThat(registry.inFlightCount()).isZero();
    }

    @Test
    void 등록한_턴은_요청_식별자와_시작_시각을_운영_확인용으로_들고_있는다() {
        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register("요청-1", 10L, 100L);

        assertThat(turn.requestId()).isEqualTo("요청-1");
        assertThat(turn.sessionId()).isEqualTo(10L);
        assertThat(turn.userId()).isEqualTo(100L);
        assertThat(turn.startedAt()).isNotNull();
    }

    @Test
    void 같은_턴의_종료를_두_번_알려도_안전하고_두_번째는_아무것도_지우지_않는다() {
        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register("요청-1", 10L, 100L);

        assertThat(registry.finish(turn)).isTrue();
        assertThat(registry.finish(turn)).isFalse();
        assertThat(registry.inFlightCount()).isZero();
    }

    @Test
    void 신규_수락을_차단한_뒤의_등록은_503_으로_거절한다() {
        registry.blockNewTurns();

        assertThat(registry.isAcceptingNewTurns()).isFalse();
        assertThatThrownBy(() -> registry.register("요청-1", 10L, 100L))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage(AiChatErrorCode.SERVER_SHUTTING_DOWN.getMessage());
    }

    @Test
    void 차단_시점에_이미_진행_중이던_턴은_그대로_남아_종료_대기의_대상이_된다() {
        registry.register("요청-1", 10L, 100L);
        registry.register("요청-2", 11L, 101L);

        assertThat(registry.blockNewTurns()).isEqualTo(2);
        assertThat(registry.inFlightCount()).isEqualTo(2);
    }

    @Test
    void 등록과_수락_차단이_경쟁해도_수락된_턴은_진행_목록에서_누락되지_않는다() throws Exception {
        int registerCount = 200;
        CountDownLatch readyToRegister = new CountDownLatch(registerCount);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<AiChatInFlightTurnRegistry.InFlightTurn> accepted = new ArrayList<>();
        AtomicInteger rejectedCount = new AtomicInteger();

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < registerCount; index++) {
                String requestId = "요청-" + index;
                workers.execute(() -> {
                    readyToRegister.countDown();
                    awaitQuietly(startTogether);
                    try {
                        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register(requestId, 10L, 100L);
                        synchronized (accepted) {
                            accepted.add(turn);
                        }
                    } catch (ServiceUnavailableException rejected) {
                        rejectedCount.incrementAndGet();
                    }
                });
            }
            // 등록이 한창일 때 수락을 차단한다 — 차단과 등록이 실제로 겹치게 한다.
            workers.execute(() -> {
                awaitQuietly(startTogether);
                registry.blockNewTurns();
            });
            assertThat(readyToRegister.await(5, TimeUnit.SECONDS)).isTrue();
            startTogether.countDown();
        }

        // 수락된 것은 하나도 빠짐없이 목록에 있고(= 종료 대기가 기다려 준다), 거절된 것은 목록에 없다.
        assertThat(registry.isAcceptingNewTurns()).isFalse();
        assertThat(accepted.size() + rejectedCount.get()).isEqualTo(registerCount);
        assertThat(registry.snapshot()).containsExactlyInAnyOrderElementsOf(accepted);
    }

    @Test
    void 진행_목록이_비면_종료_대기는_기한을_다_쓰지_않고_끝난다() throws Exception {
        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register("요청-1", 10L, 100L);
        try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            worker.execute(() -> {
                sleepQuietly(Duration.ofMillis(50));
                registry.finish(turn);
            });

            long startedAt = System.nanoTime();
            boolean drained = registry.awaitAllTurnsFinished(Duration.ofSeconds(30));
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(drained).isTrue();
            assertThat(waited).isLessThan(Duration.ofSeconds(10));
        }
    }

    @Test
    void 종료를_알리지_않은_턴이_남아_있으면_종료_대기는_기한에서_끝난다() throws Exception {
        registry.register("요청-1", 10L, 100L);

        assertThat(registry.awaitAllTurnsFinished(Duration.ofMillis(100))).isFalse();
        assertThat(registry.snapshot()).hasSize(1);
    }

    @Test
    void 전달이_끝나도_후처리가_종료를_알리기_전까지는_종료_대기가_끝나지_않는다() throws Exception {
        // 진행 목록의 단위는 "턴" 이라 SSE 전달의 종료는 목록을 건드리지 않는다 —
        // 전달만 끝난 상태를 흉내 내려면 finish 를 부르지 않은 채로 두면 된다.
        AiChatInFlightTurnRegistry.InFlightTurn turn = registry.register("요청-1", 10L, 100L);

        assertThat(registry.awaitAllTurnsFinished(Duration.ofMillis(100))).isFalse();

        registry.finish(turn);
        assertThat(registry.awaitAllTurnsFinished(Duration.ofMillis(100))).isTrue();
    }

    @Test
    void 종료_대기_중에_끝난_턴도_곧바로_반영돼_대기를_끝낸다() throws Exception {
        AiChatInFlightTurnRegistry.InFlightTurn first = registry.register("요청-1", 10L, 100L);
        AiChatInFlightTurnRegistry.InFlightTurn second = registry.register("요청-2", 11L, 101L);

        try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            worker.execute(() -> {
                sleepQuietly(Duration.ofMillis(30));
                registry.finish(first);
                sleepQuietly(Duration.ofMillis(30));
                registry.finish(second);
            });

            assertThat(registry.awaitAllTurnsFinished(Duration.ofSeconds(30))).isTrue();
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
