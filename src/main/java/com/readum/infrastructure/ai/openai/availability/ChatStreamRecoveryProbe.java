package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.exception.ExternalApiException;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 채팅의 복구 확인 — 채팅이 실제로 쓰는 <b>스트리밍</b> 경로로 확인한다.
 *
 * <p><b>왜 단발 호출로 갈음하지 않는가.</b> 채팅이 사용자에게 실패로 보이는 방식은 대부분 "스트림은 열렸는데
 * 조각이 오지 않는다" 였다. 단발 호출은 그 구간을 지나지 않으므로, 그것만 성공한 것으로 차단을 풀면
 * 열자마자 다시 같은 곳에서 막힌다. 그래서 스트림을 열고 끝까지 소비한다.
 *
 * <p><b>스트림이 오류 없이 닫힌 것만으로는 성공이 아니다.</b> 조각이 하나도 없는 스트림도, 종료 사유를 받지
 * 못한 채 연결이 끊긴 스트림도 오류 없이 완료로 보일 수 있다. 그런 것을 성공으로 읽으면 아직 생성을
 * 못 하는 공급자의 차단을 풀어 주고, 그 뒤 실제 대화들이 같은 자리에서 줄줄이 실패한다.
 * 그래서 <b>조각 안에서 모델의 종료 사유({@code finish_reason})를 실제로 관측</b>해야 성공으로 본다.
 *
 * <p>종료 사유를 <b>마지막 조각만 보고</b> 찾지 않는다 — 사용량 집계를 켜 두었으므로 마지막 조각은
 * 본문도 종료 사유도 없이 사용량만 실려 올 수 있다. 모든 조각을 훑어 한 번이라도 실렸는지를 본다.
 * 값은 가리지 않는다: 우리가 출력 상한을 몇 토큰으로 묶었으므로 정상 확인의 종료 사유는 대개
 * {@code length} 이고, {@code stop} 이든 {@code content_filter} 든 <b>모델이 생성을 시작해 끝냈다</b> 는
 * 사실은 같다 — 이 확인이 보려는 것이 바로 그것이다.
 *
 * <p>기한은 여기서 건다 — {@code blockLast(기한)} 이라 기한을 넘기면 예외로 끝나고, 그 예외가 곧
 * "공급자가 응답을 끊었다" 는 판정이 된다.
 */
class ChatStreamRecoveryProbe extends CapabilityRecoveryProbe {

    private static final String PROBE_PROMPT = "ping";

    private final ChatModel streamingChatModel;
    private final Duration streamTotalTimeout;

    ChatStreamRecoveryProbe(ChatModel streamingChatModel, Duration streamTotalTimeout) {
        super(AiAvailability.Capability.CHAT);
        this.streamingChatModel = streamingChatModel;
        this.streamTotalTimeout = streamTotalTimeout;
    }

    @Override
    protected void callProvider() {
        AtomicBoolean generationFinished = new AtomicBoolean(false);
        streamingChatModel.stream(new Prompt(new UserMessage(PROBE_PROMPT)))
                .doOnNext(chunk -> {
                    if (carriesFinishReason(chunk)) {
                        generationFinished.set(true);
                    }
                })
                .blockLast(streamTotalTimeout);
        if (!generationFinished.get()) {
            throw new ExternalApiException(AiChatErrorCode.AI_STREAM_INTERRUPTED);
        }
    }

    /** 이 조각에 모델의 종료 사유가 실려 있는가. 빈 문자열은 없는 것으로 본다. */
    private boolean carriesFinishReason(ChatResponse chunk) {
        if (chunk == null) {
            return false;
        }
        List<Generation> results = chunk.getResults();
        if (results == null) {
            return false;
        }
        for (Generation result : results) {
            if (result == null || result.getMetadata() == null) {
                continue;
            }
            String finishReason = result.getMetadata().getFinishReason();
            if (finishReason != null && !finishReason.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
