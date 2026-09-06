package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.stream.ChatDeliveryChannel;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전달 VT 의 계약 — 쓰기 순서, 기한, 실패 시 정리 범위.
 * 쓰기를 관찰해야 확인할 수 있는 항목이라 기록용 emitter 를 끼워 실행한다.
 */
class MessageStreamSseDeliveryTest {

    private static final Long SESSION_ID = 7L;
    private static final LocalDateTime SAVED_AT = LocalDateTime.of(2026, 9, 6, 12, 0, 0);

    private final MessageStreamSseSerializer serializer = new MessageStreamSseSerializer(new ObjectMapper());

    private static AiChatProperties propertiesWithDeliveryTimeout(int deliveryTimeoutSeconds) {
        return new AiChatProperties(
                new AiChatProperties.Context(8000, 2000, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(120000, 512),
                new AiChatProperties.Streaming(120, 30, deliveryTimeoutSeconds, 60, 60, 60, 2)
        );
    }

    /** 보낸 이벤트를 순서대로 기록하는 emitter. 서블릿 컨테이너 없이 쓰기 순서를 확인하기 위한 대역이다. */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> sentParts = new ArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final boolean failOnSend;
        private Runnable completionCallback;

        private RecordingEmitter(long timeoutMillis, boolean failOnSend) {
            super(timeoutMillis);
            this.failOnSend = failOnSend;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("클라이언트가 끊겼다");
            }
            builder.build().forEach(part -> sentParts.add(String.valueOf(part.getData())));
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.completionCallback = callback;
            super.onCompletion(callback);
        }

        /** 컨테이너가 요청을 끝냈을 때 부르는 콜백을 대신 발동한다. */
        void fireCompletion() {
            completionCallback.run();
        }

        String wire() {
            return String.join("", sentParts);
        }
    }

    private MessageStreamSseDelivery deliveryWith(
            AiChatProperties properties, ExecutorService executor, RecordingEmitter emitter) {
        return new MessageStreamSseDelivery(serializer, executor, properties) {
            @Override
            SseEmitter newEmitter() {
                return emitter;
            }
        };
    }

    /** 제출한 스레드에서 그대로 실행하는 실행기 — 전달이 끝난 뒤에 단언할 수 있게 한다. */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }

    private static ExecutorService rejectingExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("실행기가 닫혔다");
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return true;
            }

            @Override
            public boolean isTerminated() {
                return true;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }

    @Test
    void 델타를_받은_순서대로_쓰고_종료_이벤트로_끝낸다() {
        AiChatProperties properties = propertiesWithDeliveryTimeout(150);
        RecordingEmitter emitter = new RecordingEmitter(160_000L, false);
        MessageStreamSseDelivery delivery = deliveryWith(properties, directExecutor(), emitter);

        ChatDeliveryChannel deliveryChannel = delivery.openChannel();
        deliveryChannel.offerDelta("앞");
        deliveryChannel.offerDelta("뒤");
        deliveryChannel.completeWithSuccess(
                "앞뒤", new MessageStreamEvent.TokenCount(10, 5, 15), SAVED_AT);

        delivery.start(deliveryChannel, SESSION_ID);

        String wire = emitter.wire();
        assertThat(wire).contains("event:token").contains("event:done");
        assertThat(wire.indexOf("앞")).isLessThan(wire.indexOf("뒤"));
        assertThat(wire.indexOf("뒤")).isLessThan(wire.indexOf("event:done"));
        assertThat(emitter.completed).isTrue();
        assertThat(deliveryChannel.isClosed()).isTrue();
    }

    @Test
    void 큐가_포화됐던_연결은_완성본_교체_하나로_끝낸다() {
        // 큐 상한 2 — 세 번째 델타에서 완성본 대기로 넘어가고 대기 델타는 버려진다.
        AiChatProperties properties = propertiesWithDeliveryTimeout(150);
        RecordingEmitter emitter = new RecordingEmitter(160_000L, false);
        MessageStreamSseDelivery delivery = deliveryWith(properties, directExecutor(), emitter);

        ChatDeliveryChannel deliveryChannel = delivery.openChannel();
        deliveryChannel.offerDelta("하나");
        deliveryChannel.offerDelta("둘");
        deliveryChannel.offerDelta("셋");
        deliveryChannel.completeWithSuccess(
                "하나둘셋", new MessageStreamEvent.TokenCount(10, 5, 15), SAVED_AT);

        delivery.start(deliveryChannel, SESSION_ID);

        String wire = emitter.wire();
        assertThat(wire).contains("event:replace").contains("하나둘셋");
        assertThat(wire).doesNotContain("event:token");
    }

    @Test
    void 전달_기한이_지난_뒤에는_새_쓰기를_시작하지_않는다() throws Exception {
        AiChatProperties properties = propertiesWithDeliveryTimeout(1);
        RecordingEmitter emitter = new RecordingEmitter(11_000L, false);
        MessageStreamSseDelivery delivery = deliveryWith(properties, directExecutor(), emitter);

        // 기한의 시작점은 채널을 만든 시점이다. 전달 VT 가 늦게 실행돼도 기한이 새로 시작되지 않는다.
        ChatDeliveryChannel deliveryChannel = delivery.openChannel();
        deliveryChannel.offerDelta("보낼 수 없는 조각");
        Thread.sleep(1_200);

        delivery.start(deliveryChannel, SESSION_ID);

        assertThat(emitter.sentParts).isEmpty();
        assertThat(emitter.completed).isTrue();
        assertThat(deliveryChannel.isClosed()).isTrue();
    }

    @Test
    void 쓰기가_실패해도_전달만_끝내고_예외를_밖으로_내보내지_않는다() {
        AiChatProperties properties = propertiesWithDeliveryTimeout(150);
        RecordingEmitter emitter = new RecordingEmitter(160_000L, true);
        MessageStreamSseDelivery delivery = deliveryWith(properties, directExecutor(), emitter);

        ChatDeliveryChannel deliveryChannel = delivery.openChannel();
        deliveryChannel.offerDelta("조각");
        deliveryChannel.completeWithSuccess("조각", new MessageStreamEvent.TokenCount(1, 1, 2), SAVED_AT);

        delivery.start(deliveryChannel, SESSION_ID);

        // 전달 상태만 정리된다 — 수락한 생성·후처리를 이 실패가 건드리지 않는다.
        assertThat(deliveryChannel.isClosed()).isTrue();
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void 전달_제출이_거절되면_채널을_닫고_연결을_끝낸다() {
        AiChatProperties properties = propertiesWithDeliveryTimeout(150);
        RecordingEmitter emitter = new RecordingEmitter(160_000L, false);
        MessageStreamSseDelivery delivery = deliveryWith(properties, rejectingExecutor(), emitter);

        ChatDeliveryChannel deliveryChannel = delivery.openChannel();
        delivery.start(deliveryChannel, SESSION_ID);

        assertThat(deliveryChannel.isClosed()).isTrue();
        assertThat(emitter.completed).isTrue();
        assertThat(emitter.sentParts).isEmpty();
    }

    @Test
    void emitter_가_먼저_끝나면_채널만_정리하고_전달을_멈춘다() throws Exception {
        AiChatProperties properties = propertiesWithDeliveryTimeout(150);
        RecordingEmitter emitter = new RecordingEmitter(160_000L, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MessageStreamSseDelivery delivery = deliveryWith(properties, executor, emitter);
            ChatDeliveryChannel deliveryChannel = delivery.openChannel();
            delivery.start(deliveryChannel, SESSION_ID);

            // 컨테이너가 요청을 끝낸 상황 — 등록해 둔 onCompletion 이 채널을 닫고, 전달 VT 가 그것을 보고 끝낸다.
            emitter.fireCompletion();

            assertThat(awaitDeliveryFinished(emitter)).isTrue();
            assertThat(deliveryChannel.isClosed()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void emitter_기한은_전달_기한보다_길다() {
        // 짧으면 컨테이너가 먼저 요청을 끊어, 가장 늦게 도착하는 완성본 교체가 나가지 못한다.
        MessageStreamSseDelivery delivery = new MessageStreamSseDelivery(
                serializer, directExecutor(), propertiesWithDeliveryTimeout(150));

        assertThat(delivery.emitterTimeoutMillis()).isGreaterThan(150_000L);
    }

    /** 전달 VT 는 다른 스레드에서 도므로 끝났는지를 잠깐 기다려 확인한다. */
    private boolean awaitDeliveryFinished(RecordingEmitter emitter) throws InterruptedException {
        CountDownLatch tick = new CountDownLatch(1);
        for (int attempt = 0; attempt < 100; attempt++) {
            if (emitter.completed.get()) {
                return true;
            }
            tick.await(20, TimeUnit.MILLISECONDS);
        }
        return emitter.completed.get();
    }
}
