package com.readum.domain.aiChat.listener;

import com.readum.domain.aiChat.event.ContextSummarizeTriggerEvent;
import com.readum.domain.aiChat.service.EnqueueContextSummaryJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;

/**
 * ASSISTANT 응답 커밋 후 컨텍스트 요약 필요 여부를 판정해 job 을 멱등 적재하는 리스너.
 *
 * - AFTER_COMMIT: 메시지 저장이 확정된 뒤에만 발화한다(트랜잭션 밖에서 온 이벤트는 fallbackExecution=false 로 무시).
 * - 적재는 몇 개의 빠른 DB 쿼리 + 조건부 insert 다. 커밋 스레드(=Done 이벤트 emit 직전)를 막지 않도록
 *   가상 스레드로 offload 한다. 가상 스레드는 희소 자원이 아니라 전용 격벽 풀이 필요 없다.
 * - 적재 실패는 사용자 흐름과 무관하고, 다음 ASSISTANT 응답 때 다시 트리거되므로 예외는 로그만 남기고 던지지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextSummarizeTriggerListener {

    private final EnqueueContextSummaryJobService enqueueContextSummaryJobService;
    // 적재는 빠른 DB 단계지만 커밋 스레드(=SSE 이벤트 방출 직전)를 막지 않도록 가상 스레드로 offload 한다.
    private final Executor aiChatVirtualThreadExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContextSummarizeTrigger(ContextSummarizeTriggerEvent event) {
        aiChatVirtualThreadExecutor.execute(() -> {
            try {
                enqueueContextSummaryJobService.enqueueIfRecentMessagesExceedThreshold(event.sessionId());
            } catch (RuntimeException error) {
                log.error("컨텍스트 요약 작업 적재 실패 sessionId={}", event.sessionId(), error);
            }
        });
    }
}
