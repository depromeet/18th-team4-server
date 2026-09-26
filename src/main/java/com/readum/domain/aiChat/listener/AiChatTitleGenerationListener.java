package com.readum.domain.aiChat.listener;

import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.event.FirstAssistantResponseCompletedEvent;
import com.readum.domain.aiChat.service.AiChatSessionTitleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 첫 ASSISTANT 응답 커밋 후 세션 제목을 생성하는 리스너.
 *
 * - AFTER_COMMIT: 메시지 저장이 확정된 뒤에만 발화한다. 제목 생성(LLM) 실패가 메시지 저장을 롤백시키지 않게 하고,
 *   트랜잭션 밖(테스트/배치)에서 이벤트가 와도 기본 동작상 발화하지 않는다(fallbackExecution=false).
 * - LLM 호출은 블로킹이므로 가상 스레드로 offload 해 커밋 스레드를 막지 않는다.
 *   가상 스레드는 희소 자원이 아니라 전용 격벽 풀이 필요 없다(구 titleGenerationScheduler 대체).
 * - 제목 생성이 실패해도 사용자 흐름과 무관하므로 예외는 위로 던지지 않고 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatTitleGenerationListener {

    private final AiChatSessionTitleService aiChatSessionTitleService;
    // LLM 호출은 블로킹이므로 가상 스레드로 offload 해 커밋 스레드를 막지 않는다.
    // 가상 스레드는 희소 자원이 아니라 전용 격벽 풀이 필요 없다 (구 titleGenerationScheduler 대체).
    private final Executor aiChatVirtualThreadExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFirstAssistantResponseCompleted(FirstAssistantResponseCompletedEvent event) {
        aiChatVirtualThreadExecutor.execute(() -> {
            try {
                aiChatSessionTitleService.execute(
                        new GenerateSessionTitleCommand(event.sessionId(), List.of(event.firstUserMessage())));
            } catch (RuntimeException error) {
                log.error("세션 제목 생성 실패 sessionId={}", event.sessionId(), error);
            }
        });
    }
}
