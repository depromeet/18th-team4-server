package com.readum.domain.aiChat.stream;

import com.readum.domain.aiChat.dto.MessageStreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDeliveryChannelTest {

    private static final MessageStreamEvent.TokenCount TOKEN_COUNT = new MessageStreamEvent.TokenCount(10, 5, 15);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 6, 12, 0);
    private static final Duration NO_WAIT = Duration.ZERO;
    private static final Duration ENOUGH_WAIT = Duration.ofSeconds(5);

    private static ChatDeliveryChannel channelWithCapacity(int deltaQueueCapacity) {
        return new ChatDeliveryChannel(deltaQueueCapacity, Duration.ofSeconds(60));
    }

    @Test
    void 정상_모드는_넣은_순서대로_델타를_내보낸_뒤_done_으로_끝낸다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(4);

        channel.offerDelta("안");
        channel.offerDelta("녕");
        channel.completeWithSuccess("안녕", TOKEN_COUNT, CREATED_AT);

        assertThat(channel.poll(NO_WAIT)).isEqualTo(new MessageStreamEvent.Token("안"));
        assertThat(channel.poll(NO_WAIT)).isEqualTo(new MessageStreamEvent.Token("녕"));
        assertThat(channel.poll(NO_WAIT)).isEqualTo(new MessageStreamEvent.Done(TOKEN_COUNT, CREATED_AT));
    }

    @Test
    void 큐가_가득_차면_완성본_대기로_전환하고_아직_못_보낸_델타를_버린다() {
        ChatDeliveryChannel channel = channelWithCapacity(2);

        assertThat(channel.offerDelta("첫")).isTrue();
        assertThat(channel.offerDelta("둘")).isTrue();
        assertThat(channel.offerDelta("셋")).isFalse();

        assertThat(channel.isWaitingForFinalAnswer()).isTrue();
        assertThat(channel.queuedDeltaCount()).isZero();
    }

    @Test
    void 완성본_대기로_전환한_뒤에는_큐가_비어도_델타_모드로_돌아가지_않는다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(1);

        channel.offerDelta("첫");
        channel.offerDelta("둘");
        channel.poll(NO_WAIT);

        assertThat(channel.offerDelta("셋")).isFalse();
        assertThat(channel.queuedDeltaCount()).isZero();
    }

    @Test
    void 큐가_가득_찬_뒤에도_성공_종료_신호는_사라지지_않고_완성본_교체로_전달된다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(1);

        channel.offerDelta("첫");
        channel.offerDelta("둘");
        channel.completeWithSuccess("첫둘셋", TOKEN_COUNT, CREATED_AT);

        assertThat(channel.poll(NO_WAIT))
                .isEqualTo(new MessageStreamEvent.Replace("첫둘셋", TOKEN_COUNT, CREATED_AT));
    }

    @Test
    void 큐가_가득_찬_뒤에도_실패_종료_신호는_사라지지_않고_error_로_전달된다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(1);
        MessageStreamEvent.Error error = MessageStreamEvent.Error.of("AI_CHAT_STREAM_FAILED", "생성에 실패했습니다.");

        channel.offerDelta("첫");
        channel.offerDelta("둘");
        channel.completeWithFailure(error);

        assertThat(channel.poll(NO_WAIT)).isEqualTo(error);
    }

    @Test
    void 완성본_모드에서는_이미_꺼낸_델타_뒤에_교체_이벤트만_오고_버린_델타는_오지_않는다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(2);

        channel.offerDelta("첫");
        MessageStreamEvent alreadyStarted = channel.poll(NO_WAIT);
        channel.offerDelta("둘");
        channel.offerDelta("셋");
        channel.offerDelta("넷");
        channel.completeWithSuccess("첫둘셋넷", TOKEN_COUNT, CREATED_AT);

        assertThat(alreadyStarted).isEqualTo(new MessageStreamEvent.Token("첫"));
        assertThat(channel.poll(NO_WAIT))
                .isEqualTo(new MessageStreamEvent.Replace("첫둘셋넷", TOKEN_COUNT, CREATED_AT));
        assertThat(channel.poll(NO_WAIT)).isNull();
    }

    @Test
    void 종료_결과는_먼저_정해진_것만_남고_뒤에_온_신호는_받아들이지_않는다() throws Exception {
        MessageStreamEvent.Error error = MessageStreamEvent.Error.of("AI_CHAT_STREAM_FAILED", "생성에 실패했습니다.");

        ChatDeliveryChannel failedFirst = channelWithCapacity(2);
        failedFirst.completeWithFailure(error);
        assertThat(failedFirst.completeWithSuccess("본문", TOKEN_COUNT, CREATED_AT)).isFalse();
        assertThat(failedFirst.poll(NO_WAIT)).isEqualTo(error);

        ChatDeliveryChannel succeededFirst = channelWithCapacity(2);
        succeededFirst.completeWithSuccess("본문", TOKEN_COUNT, CREATED_AT);
        assertThat(succeededFirst.completeWithFailure(error)).isFalse();
        assertThat(succeededFirst.poll(NO_WAIT)).isEqualTo(new MessageStreamEvent.Done(TOKEN_COUNT, CREATED_AT));
    }

    @Test
    void 한_번_전달한_종료_이벤트는_다시_꺼내지_않는다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(2);

        channel.completeWithSuccess("본문", TOKEN_COUNT, CREATED_AT);
        channel.poll(NO_WAIT);

        assertThat(channel.poll(NO_WAIT)).isNull();
    }

    @Test
    void 채널을_닫으면_이후_델타와_종료_신호를_예외_없이_무시한다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(2);
        channel.offerDelta("첫");

        channel.close();

        assertThat(channel.isClosed()).isTrue();
        assertThat(channel.offerDelta("둘")).isFalse();
        assertThat(channel.completeWithSuccess("첫둘", TOKEN_COUNT, CREATED_AT)).isFalse();
        assertThat(channel.completeWithFailure(MessageStreamEvent.Error.of("코드", "메시지"))).isFalse();
        assertThat(channel.poll(NO_WAIT)).isNull();
    }

    @Test
    void 채널을_닫으면_아직_전달하지_못한_완성본을_보관하지_않는다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(1);
        channel.offerDelta("첫");
        channel.offerDelta("둘");
        channel.completeWithSuccess("첫둘셋", TOKEN_COUNT, CREATED_AT);

        channel.close();

        assertThat(channel.poll(NO_WAIT)).isNull();
        assertThat(channel.queuedDeltaCount()).isZero();
    }

    @Test
    void 닫은_채널을_여러_번_닫아도_안전하다() {
        ChatDeliveryChannel channel = channelWithCapacity(2);

        channel.close();
        channel.close();

        assertThat(channel.isClosed()).isTrue();
    }

    @Test
    @Timeout(10)
    void 기다릴_시간이_다_지나면_소비자를_풀어_준다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(2);

        assertThat(channel.poll(Duration.ofMillis(50))).isNull();
    }

    @Test
    @Timeout(10)
    void 기다리던_소비자는_뒤늦게_들어온_델타를_받는다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MessageStreamEvent> polled = executor.submit(() -> channel.poll(ENOUGH_WAIT));
            Thread.sleep(50);
            channel.offerDelta("늦은조각");

            assertThat(polled.get(5, TimeUnit.SECONDS)).isEqualTo(new MessageStreamEvent.Token("늦은조각"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void 기다리던_소비자는_채널이_닫히면_곧바로_풀려난다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MessageStreamEvent> polled = executor.submit(() -> channel.poll(ENOUGH_WAIT));
            Thread.sleep(50);
            channel.close();

            assertThat(polled.get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void 여러_스레드가_동시에_델타를_넣어도_큐_상한만큼만_받고_나머지는_잔류하지_않는다() throws Exception {
        int deltaQueueCapacity = 8;
        int producerCount = 6;
        int offersPerProducer = 200;
        ChatDeliveryChannel channel = channelWithCapacity(deltaQueueCapacity);
        AtomicInteger acceptedCount = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(producerCount);
        try {
            List<Future<?>> producers = new ArrayList<>();
            for (int producer = 0; producer < producerCount; producer++) {
                producers.add(executor.submit(() -> {
                    startLine.await();
                    for (int offer = 0; offer < offersPerProducer; offer++) {
                        if (channel.offerDelta("조각")) {
                            acceptedCount.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            startLine.countDown();
            for (Future<?> producer : producers) {
                producer.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // 소비자가 없으므로 상한만큼만 들어가고, 그 뒤 첫 거절에서 전환하며 들어가 있던 델타까지 버린다.
        assertThat(acceptedCount.get()).isEqualTo(deltaQueueCapacity);
        assertThat(channel.isWaitingForFinalAnswer()).isTrue();
        assertThat(channel.queuedDeltaCount()).isZero();
    }

    @Test
    @Timeout(30)
    void 생성과_전달과_종료가_동시에_경쟁해도_종료_이벤트_뒤에_델타가_오지_않는다() throws Exception {
        ChatDeliveryChannel channel = channelWithCapacity(4);
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<List<MessageStreamEvent>> consumer = executor.submit(() -> {
                startLine.await();
                List<MessageStreamEvent> delivered = new ArrayList<>();
                while (true) {
                    MessageStreamEvent event = channel.poll(ENOUGH_WAIT);
                    if (event == null) {
                        return delivered;
                    }
                    delivered.add(event);
                    if (!(event instanceof MessageStreamEvent.Token)) {
                        return delivered;
                    }
                }
            });
            Future<?> producer = executor.submit(() -> {
                startLine.await();
                for (int offer = 0; offer < 2_000; offer++) {
                    channel.offerDelta("조각" + offer);
                }
                return null;
            });
            Future<?> finisher = executor.submit(() -> {
                startLine.await();
                Thread.sleep(20);
                channel.completeWithSuccess("최종 답변", TOKEN_COUNT, CREATED_AT);
                return null;
            });

            startLine.countDown();
            producer.get(20, TimeUnit.SECONDS);
            finisher.get(20, TimeUnit.SECONDS);
            List<MessageStreamEvent> delivered = consumer.get(20, TimeUnit.SECONDS);

            MessageStreamEvent last = delivered.getLast();
            assertThat(last).isNotInstanceOf(MessageStreamEvent.Token.class);
            assertThat(delivered.subList(0, delivered.size() - 1))
                    .allMatch(event -> event instanceof MessageStreamEvent.Token);
            assertThat(channel.queuedDeltaCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 남은_전달_기한은_채널을_만든_시점부터_줄어든다() {
        ChatDeliveryChannel fresh = new ChatDeliveryChannel(2, Duration.ofSeconds(30));
        ChatDeliveryChannel expired = new ChatDeliveryChannel(2, Duration.ZERO);

        assertThat(fresh.remainingDeliveryTime())
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(expired.remainingDeliveryTime()).isZero();
    }
}
