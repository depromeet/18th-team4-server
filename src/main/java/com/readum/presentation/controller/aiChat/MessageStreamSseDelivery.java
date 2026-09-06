package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.stream.ChatDeliveryChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 연결 하나의 SSE 쓰기를 <b>혼자</b> 맡는 전달 담당. 채널({@link ChatDeliveryChannel})에서 이벤트를 하나씩 꺼내
 * 순서대로 쓰고, 마지막 이벤트를 쓰거나 더 쓸 수 없게 되면 연결을 닫는다.
 *
 * <p><b>emitter 를 쓰는 주체는 이 전달 스레드 하나다.</b> 생성 구독도 후처리도 emitter 를 직접 만지지 않고
 * 채널에 넣기만 한다. 쓰기 주체가 하나라야 델타 → 종료 이벤트(done·replace·error)의 순서가 지켜진다 —
 * 여러 주체가 각자 쓰면 저장·정산이 끝난 뒤의 종료 이벤트가 아직 못 나간 델타를 앞질러 나갈 수 있다.
 *
 * <p><b>전달의 종료는 생성·후처리의 종료가 아니다.</b> 쓰기 실패(클라이언트 이탈)·emitter 종료·기한 초과는
 * 채널만 닫고 끝낸다. 이미 수락한 생성과 저장·정산은 그대로 끝까지 간다.
 *
 * <h2>기한 두 개의 관계</h2>
 * <ul>
 *   <li><b>전달 기한</b>({@code delivery-timeout-seconds}) — 채널을 만든 시점부터 잰다. 전달 VT 가 실제로
 *       실행을 시작한 시점으로 재면 실행이 밀릴수록 기한도 밀려 상한 구실을 못 하므로, 시작점은 채널이 쥐고 있다
 *       ({@link ChatDeliveryChannel#remainingDeliveryTime()}). 이 클래스는 남은 시간을 물어보기만 한다.</li>
 *   <li><b>emitter 자체 기한</b> — 서블릿 비동기 요청의 기한이다. 이 값이 전달 기한보다 짧으면 컨테이너가 먼저
 *       요청을 끊어 <b>전달 기한이 상한 구실을 못 한다</b>. 특히 완성본 교체(replace)는 생성 전체 기한이 다 지난 뒤
 *       저장·정산까지 끝나야 나가므로 가장 늦게 도착하는 이벤트다. 그래서 emitter 기한은 전달 기한보다
 *       {@link #EMITTER_TIMEOUT_MARGIN} 만큼 길게 잡아 <b>우리 기한이 먼저 끝나게</b> 한다.</li>
 * </ul>
 *
 * <h2>기한 이후에 보장하는 것과 못 하는 것</h2>
 * 기한이 지나면 <b>새 쓰기를 시작하지 않는다</b>. 이미 소켓에 매달린 쓰기를 회수하지는 못한다 —
 * interrupt 는 블로킹 소켓 쓰기를 깨우지 못하고, {@code complete()} 도 진행 중인 쓰기를 중단시키지 않는다.
 * 그 회수는 컨테이너의 연결·비동기 기한이 하는 일이다.
 *
 * <h2>애플리케이션 큐 밖의 버퍼</h2>
 * 전달 큐 상한은 <b>우리가 아직 안 보낸 델타 수</b>만 제한한다. 그 밖에 최소 두 겹이 더 있다.
 * <ul>
 *   <li>{@code ResponseBodyEmitter} 의 초기화 전 내부 목록 — 컨트롤러가 emitter 를 돌려주기 전에 보낸 이벤트는
 *       {@code earlySendAttempts}(상한 없는 Set)에 쌓였다가 초기화 시점에 한꺼번에 나간다(스프링 7.0.6 바이트코드 확인).
 *       전달 VT 가 컨트롤러의 반환보다 먼저 쓰기 시작할 수 있으므로 이 창이 실제로 열린다. 다만 그 창은
 *       요청 스레드가 emitter 를 돌려주기까지의 짧은 구간이고, 그 사이 우리가 보낼 수 있는 양도 채널의 큐 상한에
 *       묶여 있다.</li>
 *   <li>서블릿 컨테이너의 응답 버퍼 — 그 뒤로는 컨테이너와 소켓 송신 버퍼가 받아 준다. 여기가 차면 쓰기가 막히고,
 *       그때 우리가 하는 일은 전달 VT 하나가 기한까지 매달리는 것뿐이다(생성은 계속된다).</li>
 * </ul>
 * 그러므로 <b>전달 큐 상한만 보고 전송 버퍼 전체가 제한됐다고 판단하지 않는다.</b> 실제 상한은 측정으로 정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageStreamSseDelivery {

    /**
     * emitter 기한을 전달 기한보다 이만큼 길게 둔다. 둘이 같으면 어느 쪽이 먼저 만료될지 정해지지 않아,
     * 전달 VT 가 마지막 이벤트를 쓰기 직전에 컨테이너가 요청을 끊는 경쟁이 생긴다.
     */
    private static final Duration EMITTER_TIMEOUT_MARGIN = Duration.ofSeconds(10);

    /** 전달이 끝난 사유 — 한 줄 형식을 공유하고 사유만 갈아 끼운다(같은 종료를 두 가지 문장으로 남기지 않기 위해서다). */
    private static final String END_CHANNEL_CLOSED = "채널 종료";
    private static final String END_DEADLINE_BEFORE_POLL = "전달 기한 소진 — 새 쓰기를 시작하지 않는다";
    private static final String END_DEADLINE_WHILE_WAITING = "전달 기한 소진 — 이벤트를 기다리다 기한이 끝났다";
    private static final String END_DEADLINE_BEFORE_WRITE = "전달 기한 소진 — 꺼낸 이벤트를 쓰지 않고 끝낸다";

    private final MessageStreamSseSerializer messageStreamSseSerializer;
    private final ExecutorService aiChatDeliveryExecutor;
    private final AiChatProperties aiChatProperties;

    /**
     * 이 연결의 전달 통로를 연다. 전달 기한은 <b>이 시점부터</b> 시작한다.
     * 생성 구독보다 먼저 만들어야 첫 청크가 갈 곳이 있다.
     */
    public ChatDeliveryChannel openChannel() {
        return ChatDeliveryChannel.open(aiChatProperties.streaming());
    }

    /**
     * 전달을 시작하고 컨트롤러가 돌려줄 {@link SseEmitter} 를 만든다.
     *
     * <p>제출이 거절되면(종료 절차로 실행기가 닫힌 뒤) 이 연결로는 아무것도 보내지 못한다.
     * 그때도 <b>채널만 정리하고 연결을 닫는다</b> — 이미 수락한 생성·후처리는 그대로 진행돼 답변은 DB 에 남는다.
     */
    public SseEmitter start(ChatDeliveryChannel deliveryChannel, Long sessionId) {
        SseEmitter emitter = newEmitter();
        // emitter 가 어떤 이유로 끝나든(정상 완료·기한 초과·전송 오류) 전달 상태만 정리한다.
        // 생성·후처리는 이 신호를 보지 않는다.
        emitter.onCompletion(deliveryChannel::close);
        emitter.onTimeout(() -> {
            log.info("SSE emitter 기한 초과 — 전달만 정리한다 sessionId={}", sessionId);
            deliveryChannel.close();
        });
        emitter.onError(sendError -> {
            log.info("SSE emitter 오류 — 전달만 정리한다 sessionId={} cause={}", sessionId, sendError.toString());
            deliveryChannel.close();
        });

        try {
            aiChatDeliveryExecutor.execute(() -> deliver(emitter, deliveryChannel, sessionId));
        } catch (RejectedExecutionException deliveryRejected) {
            log.warn("SSE 전달 제출 거절 — 이 연결로는 보내지 않는다(생성·저장은 계속) sessionId={} cause={}",
                    sessionId, deliveryRejected.toString());
            deliveryChannel.close();
            completeQuietly(emitter, sessionId);
        }
        return emitter;
    }

    /**
     * 전달 VT 의 본체. 남은 전달 기한 안에서만 이벤트를 꺼내고, <b>꺼낸 뒤 실제 쓰기 직전에 다시</b>
     * 종료 여부와 남은 기한을 확인한다 — 큐에서 기다린 시간 때문에 이미 기한이 지났거나 연결이 닫혔을 수 있는데,
     * 그 사이를 확인하지 않으면 기한 뒤에 새 쓰기를 시작하게 된다.
     */
    private void deliver(SseEmitter emitter, ChatDeliveryChannel deliveryChannel, Long sessionId) {
        try {
            while (true) {
                Duration remaining = deliveryChannel.remainingDeliveryTime();
                if (remaining.isZero()) {
                    logDeliveryEnd(END_DEADLINE_BEFORE_POLL, deliveryChannel, sessionId);
                    break;
                }
                MessageStreamEvent event = deliveryChannel.poll(remaining);
                if (event == null) {
                    // 채널이 닫혔거나 기다릴 시간을 다 썼다 — 어느 쪽이든 전달은 여기서 끝난다.
                    // 다만 원인이 다르다(앞은 emitter 종료·클라이언트 이탈, 뒤는 생성이 기한 안에 끝나지 않음).
                    // 구분해 남기지 않으면 기한으로 끝난 연결이 로그에 아무 흔적도 남기지 않는다.
                    logDeliveryEnd(
                            deliveryChannel.isClosed() ? END_CHANNEL_CLOSED : END_DEADLINE_WHILE_WAITING,
                            deliveryChannel, sessionId);
                    break;
                }
                if (deliveryChannel.isClosed()) {
                    logDeliveryEnd(END_CHANNEL_CLOSED, deliveryChannel, sessionId);
                    break;
                }
                if (deliveryChannel.remainingDeliveryTime().isZero()) {
                    logDeliveryEnd(END_DEADLINE_BEFORE_WRITE, deliveryChannel, sessionId);
                    break;
                }
                emitter.send(messageStreamSseSerializer.toSseEvent(event));
                if (!(event instanceof MessageStreamEvent.Token)) {
                    // done·replace·error 는 이 연결의 마지막 이벤트다.
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.info("SSE 전달이 중단됐다 sessionId={}", sessionId);
        } catch (IOException | IllegalStateException sendError) {
            // 클라이언트 이탈 추정(IllegalStateException 은 이미 닫힌 emitter 에 쓴 경우).
            // 전송만 멈추고 생성·저장·정산은 그대로 진행된다.
            log.info("SSE 전송 실패(클라이언트 이탈 추정) sessionId={} cause={}", sessionId, sendError.toString());
        } catch (RuntimeException unexpectedDeliveryError) {
            log.error("SSE 전달 중 예기치 못한 실패 sessionId={}", sessionId, unexpectedDeliveryError);
        } finally {
            deliveryChannel.close();
            completeQuietly(emitter, sessionId);
        }
    }

    /**
     * 전달이 끝난 사유를 한 줄로 남긴다. 채널 상태(아직 못 보낸 델타 수·완성본 대기 모드)를 함께 실어,
     * 기한으로 끝난 연결이 <b>무엇을 못 보내고</b> 끝났는지 로그만으로 구분할 수 있게 한다.
     * 두 값을 읽느라 채널 잠금을 잠깐 더 잡지만, 그 안에서 하는 일은 필드 읽기뿐이다.
     */
    private void logDeliveryEnd(String reason, ChatDeliveryChannel deliveryChannel, Long sessionId) {
        log.info("SSE 전달 종료({}) sessionId={} queuedDeltaCount={} waitingForFinalAnswer={}",
                reason, sessionId, deliveryChannel.queuedDeltaCount(),
                deliveryChannel.isWaitingForFinalAnswer());
    }

    /**
     * emitter 를 만드는 한 지점. 이 클래스가 쓰기를 어떻게 하는지는 emitter 없이는 확인할 수 없어
     * 테스트가 기록용 emitter 로 바꿔 끼울 수 있도록 열어 둔다. 운영 경로는 이 기본 구현을 쓴다.
     */
    SseEmitter newEmitter() {
        return new SseEmitter(emitterTimeoutMillis());
    }

    long emitterTimeoutMillis() {
        return Duration.ofSeconds(aiChatProperties.streaming().deliveryTimeoutSeconds())
                .plus(EMITTER_TIMEOUT_MARGIN)
                .toMillis();
    }

    private void completeQuietly(SseEmitter emitter, Long sessionId) {
        try {
            emitter.complete();
        } catch (RuntimeException alreadyClosed) {
            log.info("SSE 종료 처리 생략(이미 닫힘) sessionId={} cause={}", sessionId, alreadyClosed.toString());
        }
    }
}
