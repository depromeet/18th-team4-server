package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 스트리밍 생성 한 턴의 청크를 순서대로 받아 누적하고, 스트림이 끝나면 최종 판정을 내는 컴포넌트.
 *
 * <p><b>정상 완료 판정</b>은 다음 넷을 모두 만족하는 경우다.
 * <ol>
 *   <li>오류·기한 초과 없이 스트림이 끝났다(onComplete)</li>
 *   <li>공급자가 준 종료 사유가 {@code STOP} 이다</li>
 *   <li>유효한 최종 사용량이 있다 ({@link AiChatStreamChunk#hasValidUsage()})</li>
 *   <li>사용자에게 보여줄 수 있는 전체 본문이 있다(빈 본문 아님)</li>
 * </ol>
 * OpenAI 프로토콜의 {@code [DONE]} 표식 관찰은 추가 성공 조건으로 두지 않는다 — Spring AI 가 그 표식을
 * 내부에서 걷어내 애플리케이션까지 오지 않기 때문이다. 위 넷이 갖춰졌으면 표식이 보이지 않았다는 이유로
 * 실패로 만들지 않는다.
 *
 * <p><b>한 번 끝나면 뒤집히지 않는다.</b> 종료(정상·오류·기한 초과)는 먼저 도착한 신호 하나만 반영하고,
 * 그 뒤에 온 종료 신호는 처음 판정을 그대로 돌려준다. 중복 종료 훅
 * (doOnComplete/doOnError/doOnCancel/doFinally 조합)이 겹쳐 불려도 판정이 바뀌거나 두 번 확정되지 않는다.
 * 이미 STOP 과 사용량을 받았더라도 그 뒤에 오류가 오면 실패다.
 *
 * <p><b>실행 전제:</b> {@link #accept(AiChatStreamChunk)} 는 한 스트림의 신호가 직렬로 전달된다는 전제로
 * 동기화 없이 누적한다(Reactor 시퀀스의 신호 직렬성). 반면 종료는 기한 타이머 등 다른 스레드와 겹칠 수 있어
 * 원자적으로 한 번만 확정한다.
 *
 * <p><b>후속 작업 접점:</b>
 * <ul>
 *   <li>Task 7(생성 구독)이 턴마다 하나씩 만들어 청크 콜백에서 {@link #accept}, 종료 훅에서
 *       {@link #completeNormally()} / {@link #failWithStreamError()} / {@link #failWithTimeout()} 를 부른다.</li>
 *   <li>Task 3(전달 채널)은 포화 후 완성본 교체에 쓸 본문을 {@link #accumulatedContent()} 로 읽는다.
 *       다만 클라이언트로 보내는 완성본은 저장·정산이 커밋된 뒤의 값이어야 한다.</li>
 * </ul>
 */
public class AiChatGenerationAccumulator {

    /** 공급자가 정상 종료로 알려주는 사유. Spring AI 2.0.0-M4 는 원본 {@code stop} 을 {@code STOP} 으로 올려 준다. */
    private static final String NORMAL_FINISH_REASON = "STOP";

    private final StringBuilder accumulatedContent = new StringBuilder();
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private String finishReason;
    private AiChatStreamChunk lastValidUsage;
    private AiChatGenerationOutcome outcome;

    /**
     * 청크 1건을 누적한다. 본문 없이 종료 사유나 사용량만 실린 청크도 정상 모양이므로 걸러내지 않는다.
     * 사용량은 더하지 않고 <b>마지막으로 받은 유효 값</b>을 그 턴의 실측으로 쓴다.
     * 종료 사유도 마지막 값을 쓴다 — 공급자가 한 번만 주지만, 두 번 오면 나중 값이 그 턴의 결론이다.
     */
    public void accept(AiChatStreamChunk chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.hasDelta()) {
            accumulatedContent.append(chunk.delta());
        }
        if (chunk.hasFinishReason()) {
            finishReason = chunk.finishReason();
        }
        if (chunk.hasValidUsage()) {
            lastValidUsage = chunk;
        }
    }

    /** 지금까지 누적한 본문. 아직 생성 중일 수도 있다. */
    public String accumulatedContent() {
        return accumulatedContent.toString();
    }

    /** 스트림이 오류·기한 초과 없이 끝났을 때의 판정. 네 조건을 모두 만족해야 성공이다. */
    public AiChatGenerationOutcome completeNormally() {
        return terminate(judgeCompletedStream());
    }

    /** 스트림이 오류로 끝났을 때(onError·상류 취소 포함)의 판정. 무조건 실패다. */
    public AiChatGenerationOutcome failWithStreamError() {
        return terminate(AiChatGenerationOutcome.Status.STREAM_ERROR);
    }

    /** 생성 기한(전체 또는 무응답)에 걸려 끊었을 때의 판정. 무조건 실패다. */
    public AiChatGenerationOutcome failWithTimeout() {
        return terminate(AiChatGenerationOutcome.Status.TIMED_OUT);
    }

    /** 이미 종료 판정이 났는지. 늦게 도착한 종료 신호를 그냥 흘려보낼 때 쓴다. */
    public boolean isTerminated() {
        return terminated.get();
    }

    /**
     * 실패 사유를 앞에서부터 하나씩 본다. 성립 순서는 "공급자가 뭐라고 끝냈는가 → 정산할 수 있는가 →
     * 사용자에게 보여줄 것이 있는가" 로, 원인을 좁히기 쉬운 순서다.
     */
    private AiChatGenerationOutcome.Status judgeCompletedStream() {
        if (finishReason == null) {
            return AiChatGenerationOutcome.Status.NO_FINISH_REASON;
        }
        if (!NORMAL_FINISH_REASON.equalsIgnoreCase(finishReason)) {
            return AiChatGenerationOutcome.Status.ABNORMAL_FINISH_REASON;
        }
        if (lastValidUsage == null) {
            return AiChatGenerationOutcome.Status.NO_USAGE;
        }
        if (accumulatedContent.isEmpty()) {
            return AiChatGenerationOutcome.Status.EMPTY_CONTENT;
        }
        return AiChatGenerationOutcome.Status.SUCCESS;
    }

    private synchronized AiChatGenerationOutcome terminate(AiChatGenerationOutcome.Status status) {
        if (!terminated.compareAndSet(false, true)) {
            return outcome;
        }
        outcome = new AiChatGenerationOutcome(
                status,
                accumulatedContent.toString(),
                finishReason,
                lastValidUsage == null ? null : lastValidUsage.inputTokens(),
                lastValidUsage == null ? null : lastValidUsage.outputTokens(),
                lastValidUsage == null ? null : lastValidUsage.totalTokens()
        );
        return outcome;
    }
}
