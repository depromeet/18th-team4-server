package com.readum.domain.aiChat.stream;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.MessageStreamEvent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 연결 하나의 전달 통로. 생성(청크 수신)과 전달(SSE 쓰기)의 진행을 떼어 놓기 위한 <b>순수 동시성 상태</b>다 —
 * SSE·DB·Reactor 를 알지 못하고, 스스로 실행하지도 않는다. 상태를 읽고 실제로 쓰는 것은 전달 스레드다.
 *
 * <p>세 참여자가 한 채널을 함께 쓴다.
 * <ul>
 *   <li><b>생성 구독</b> — 청크마다 {@link #offerDelta(String)}. 큐 빈자리를 기다리지 않는다.</li>
 *   <li><b>후처리</b> — 저장·정산이 끝나면 {@link #completeWithSuccess} 또는 {@link #completeWithFailure}.</li>
 *   <li><b>전달 스레드</b> — {@link #poll(Duration)} 로 하나씩 꺼내 순서대로 쓰고, 끝나면 {@link #close()}.</li>
 * </ul>
 *
 * <p>델타 큐와 <b>종료 결과 통로를 따로 둔다</b>. 하나로 합치면 큐가 포화됐을 때 완료·실패 신호까지 자리를 못 잡아
 * 사라진다 — 전달은 포기해도 되지만 종료 신호는 포기할 수 없다.
 *
 * <p>델타 큐가 가득 차면 <b>완성본 대기 모드</b>로 한 번 전환하고 그대로 고정된다. 전환 시 아직 못 보낸 델타를 버리고,
 * 이후 델타는 큐에 넣지 않는다(생성 쪽은 자기 누적 버퍼를 계속 갱신한다). 일부 델타를 버린 뒤 정상 스트림처럼 이어
 * 보내면 클라이언트가 이어붙인 본문에 구멍이 남으므로 델타 모드로 돌아가지 않는다. 대신 저장·정산이 커밋된 뒤
 * 전체 본문을 실은 {@link MessageStreamEvent.Replace} 하나로 교체한다.
 *
 * <p>모든 상태 변경은 하나의 잠금 아래에서 일어난다. 그래서 전환·큐 입력·소비·종료가 겹쳐도 포화 이후의 델타가
 * 큐에 남지 않는다. 잠금을 잡고 있는 구간은 큐·모드·종료 결과를 만지는 짧은 코드뿐이며, SSE 쓰기 같은 느린 작업은
 * {@link #poll(Duration)} 이 값을 돌려준 <b>뒤에</b> 잠금 밖에서 일어난다.
 *
 * <p><b>큐 상한은 프로세스 전체의 메모리를 제한하지 않는다.</b> 여기서 제한하는 것은 연결 1개가 아직 못 보낸
 * 델타 개수뿐이다. 델타 문자열 자체의 길이, 생성 쪽 누적 답변, 동시에 열린 연결 수, 서블릿 컨테이너 내부 전송 버퍼는
 * 이 상한 밖에 있다. OOM 여부는 이 값이 아니라 그것들을 함께 재서 판단해야 한다.
 */
public final class ChatDeliveryChannel {

    private final int deltaQueueCapacity;
    private final Duration deliveryTimeout;

    /** 전달 기한의 시작점. 전달 스레드가 실제로 실행을 시작한 시점으로 재면 실행이 밀릴수록 기한도 밀려 상한 구실을 못 한다. */
    private final long createdAtNanos;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition deliverable = lock.newCondition();

    /** 아직 못 보낸 델타. 완성본 대기로 넘어가면 비우고 다시 채우지 않는다. */
    private final ArrayDeque<String> deltaQueue;

    /** 큐 포화로 완성본 대기 모드로 넘어갔는지. 한 번 켜지면 꺼지지 않는다. */
    private boolean waitingForFinalAnswer;

    /** 아직 전달하지 않은 종료 결과. 한 번만 정해지고, 전달하고 나면 참조를 놓는다. */
    private MessageStreamEvent terminalEvent;

    private boolean terminalDelivered;
    private boolean closed;

    /** 운영 경로의 생성 지점 — 기한·큐 상한을 설정 한 곳에서 가져와 연결마다 새 채널을 연다. */
    public static ChatDeliveryChannel open(AiChatProperties.Streaming streaming) {
        return new ChatDeliveryChannel(
                streaming.deliveryQueueCapacity(),
                Duration.ofSeconds(streaming.deliveryTimeoutSeconds())
        );
    }

    ChatDeliveryChannel(int deltaQueueCapacity, Duration deliveryTimeout) {
        this.deltaQueueCapacity = deltaQueueCapacity;
        this.deliveryTimeout = deliveryTimeout;
        this.deltaQueue = new ArrayDeque<>(deltaQueueCapacity);
        this.createdAtNanos = System.nanoTime();
    }

    /**
     * 본문 조각 하나를 전달 큐에 넣어 본다. <b>기다리지 않는다</b> — 자리가 없으면 그 자리에서 완성본 대기로 넘어간다.
     * 본문이 있는 조각만 넘긴다({@code AiChatStreamChunk#hasDelta}).
     *
     * @return 큐에 들어갔으면 true. false 는 <b>델타로는 더 보낼 수 없다</b>는 뜻이며, 이번에 포화로 전환됐든
     * 이미 전환·종료·닫힘 상태였든 구분하지 않는다. 어느 쪽이든 생성 쪽이 할 일은 같다 — 누적만 계속한다.
     */
    public boolean offerDelta(String delta) {
        lock.lock();
        try {
            if (closed || waitingForFinalAnswer || terminalEvent != null || terminalDelivered) {
                return false;
            }
            if (deltaQueue.size() >= deltaQueueCapacity) {
                waitingForFinalAnswer = true;
                deltaQueue.clear();
                deliverable.signalAll();
                return false;
            }
            deltaQueue.addLast(delta);
            deliverable.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 저장·정산이 커밋된 뒤 성공으로 끝낸다. 어떤 이벤트로 끝낼지는 <b>채널이 자기 모드를 보고</b> 정한다 —
     * 정상 모드면 남은 델타 뒤 {@link MessageStreamEvent.Done}, 완성본 대기 모드면 전체 본문을 실은
     * {@link MessageStreamEvent.Replace}. 호출자가 둘을 고르게 두면 모드와 어긋난 이벤트가 나갈 수 있어 여기서 묶는다.
     *
     * <p>정상 모드에서는 finalContent 를 <b>보관하지 않는다</b>. 클라이언트가 이어붙여 온 본문이 곧 최종 답변이라
     * 다시 실어 보낼 이유가 없고, 보관하면 연결마다 답변 하나치 메모리를 더 쥐고 있게 된다.
     *
     * @return 종료 결과로 실렸으면 true. 이미 종료 결과가 정해졌거나 채널이 닫혔으면 false 이고, 이때 넘어온
     * 본문은 <b>보관하지 않고 버린다</b>. false 는 전달 여부일 뿐 저장·정산의 성패와는 무관하다.
     */
    public boolean completeWithSuccess(
            String finalContent,
            MessageStreamEvent.TokenCount tokenCount,
            LocalDateTime createdAt
    ) {
        lock.lock();
        try {
            if (closed || terminalEvent != null || terminalDelivered) {
                return false;
            }
            terminalEvent = waitingForFinalAnswer
                    ? new MessageStreamEvent.Replace(finalContent, tokenCount, createdAt)
                    : new MessageStreamEvent.Done(tokenCount, createdAt);
            deliverable.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 실패로 끝낸다. 종료 통로는 한 번만 정해지므로 <b>실패 뒤에 온 성공 신호는 받아들이지 않는다</b> —
     * 실패한 턴이 성공 결과로 바뀌어 나가지 않게 하는 것이 이 한 번 제한의 목적이다.
     *
     * @return 종료 결과로 실렸으면 true, 이미 정해졌거나 닫혔으면 false.
     */
    public boolean completeWithFailure(MessageStreamEvent.Error error) {
        lock.lock();
        try {
            if (closed || terminalEvent != null || terminalDelivered) {
                return false;
            }
            terminalEvent = error;
            deliverable.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 다음에 쓸 이벤트 하나를 꺼낸다. 없으면 maxWait 만큼만 기다린다 — 전달 스레드가 남은 전달 기한
     * ({@link #remainingDeliveryTime()})을 넘겨 기한 밖에서 계속 매달리지 않게 한다.
     *
     * <p>내보내는 순서는 <b>남은 델타 → 종료 이벤트</b>다. 완성본 대기 모드에서는 전환 시점에 델타를 버렸으므로
     * 남은 델타가 없고 곧바로 교체 이벤트가 나간다. 이미 꺼내 간 델타의 쓰기는 그대로 끝나며, 교체 이벤트가
     * 그것을 추월하지 않는다 — 이 채널에서 꺼내 쓰는 주체가 전달 스레드 하나이기 때문이다.
     *
     * @return {@link MessageStreamEvent.Token} 이면 뒤에 더 올 수 있고, Done·Replace·Error 면 그것이 마지막이다.
     * null 은 기다릴 시간이 다 됐거나 채널이 닫혔다는 뜻으로, 어느 쪽이든 전달을 끝낸다.
     */
    public MessageStreamEvent poll(Duration maxWait) throws InterruptedException {
        long remainingNanos = maxWait.toNanos();
        lock.lock();
        try {
            while (true) {
                if (closed) {
                    return null;
                }
                String delta = deltaQueue.pollFirst();
                if (delta != null) {
                    return new MessageStreamEvent.Token(delta);
                }
                if (terminalEvent != null) {
                    MessageStreamEvent terminal = terminalEvent;
                    // 전달했으니 참조를 놓는다. Replace 는 답변 전체를 들고 있어 계속 쥐고 있을 이유가 없다.
                    terminalEvent = null;
                    terminalDelivered = true;
                    return terminal;
                }
                if (terminalDelivered || remainingNanos <= 0) {
                    return null;
                }
                remainingNanos = deliverable.awaitNanos(remainingNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 이 연결의 전달을 끝내고 들고 있던 것을 놓는다. 전달 스레드가 어떤 이유로 끝나든(정상 종료, 쓰기 실패,
     * 기한 초과, 클라이언트 단절) 불러야 한다. 여러 번 불러도 안전하다.
     *
     * <p>닫은 뒤에는 델타도 종료 신호도 받지 않고 <b>예외 없이 무시한다</b>. 전달이 끝났다고 생성·후처리를
     * 중단시키지 않기 위해서다 — 클라이언트가 끊겨도 저장·정산은 끝까지 간다.
     */
    public void close() {
        lock.lock();
        try {
            closed = true;
            deltaQueue.clear();
            terminalEvent = null;
            deliverable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 채널을 만든 시점부터 잰 전달 기한의 남은 시간. 이미 지났으면 {@link Duration#ZERO}. */
    public Duration remainingDeliveryTime() {
        long remainingNanos = deliveryTimeout.toNanos() - (System.nanoTime() - createdAtNanos);
        return remainingNanos > 0 ? Duration.ofNanos(remainingNanos) : Duration.ZERO;
    }

    /** 큐 포화로 완성본 대기 모드로 넘어갔는지. 생성·전달 양쪽이 자기 판단에 쓰고, 측정에도 쓴다. */
    public boolean isWaitingForFinalAnswer() {
        lock.lock();
        try {
            return waitingForFinalAnswer;
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** 아직 못 보낸 델타 개수. 전달 정체와 큐 깊이를 재기 위한 관측용이다. */
    public int queuedDeltaCount() {
        lock.lock();
        try {
            return deltaQueue.size();
        } finally {
            lock.unlock();
        }
    }
}
