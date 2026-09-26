package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 대화 모델 하나를 공급자 상태 보호로 감싼 것. 감싸는 자리는 <b>모델 경계</b>다 —
 * 그래야 그 모델을 부르는 모든 경로(ChatClient 를 거치든 직접 부르든)가 같은 보호를 받는다.
 *
 * <p>기능마다 다른 프로젝트로 나가므로 감싼 것도 기능별로 하나씩이다. 어느 기능인지는 이 객체가 들고 있다 —
 * 호출 지점이 매번 넘기면 한 곳만 틀려도 호출은 A 프로젝트로 나가고 상태는 B 기능에 쌓인다.
 */
public class ProtectedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AiAvailability.Capability capability;
    private final AiProviderCallGuard callGuard;

    /** 스트리밍 응답의 무응답 기한 · 전체 기한. 스트리밍을 쓰지 않는 기능은 {@code null} 이라 기한을 걸지 않는다. */
    private final Duration streamIdleTimeout;
    private final Duration streamTotalTimeout;

    public ProtectedChatModel(
            ChatModel delegate, AiAvailability.Capability capability, AiProviderCallGuard callGuard) {
        this(delegate, capability, callGuard, null, null);
    }

    public ProtectedChatModel(
            ChatModel delegate,
            AiAvailability.Capability capability,
            AiProviderCallGuard callGuard,
            Duration streamIdleTimeout,
            Duration streamTotalTimeout
    ) {
        this.delegate = delegate;
        this.capability = capability;
        this.callGuard = callGuard;
        this.streamIdleTimeout = streamIdleTimeout;
        this.streamTotalTimeout = streamTotalTimeout;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return callGuard.callProtected(capability, () -> delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return callGuard.streamProtected(capability, () -> withDeadlines(delegate.stream(prompt)));
    }

    /**
     * 응답 기한 둘을 <b>보호 구간 안쪽</b>에 건다 — 기한 초과가 취소가 아니라 오류 신호로 도착해야
     * 공급자 상태에 반영되기 때문이다(자세한 이유는 {@link AiProviderCallGuard} 주석).
     * 타이머를 새로 더한 것이 아니라, 도메인이 걸던 같은 기한을 이 자리로 옮겨 온 것이다.
     *
     * <p>무응답 기한은 청크를 받을 때마다 다시 시작되고, 전체 기한은 구독 시점부터 한 번만 잰다.
     * 둘 다 {@link TimeoutException} 으로 끝나므로 도메인의 기한 초과 판정은 그대로다.
     */
    private Flux<ChatResponse> withDeadlines(Flux<ChatResponse> stream) {
        if (streamIdleTimeout == null || streamTotalTimeout == null) {
            return stream;
        }
        return stream
                .timeout(streamIdleTimeout)
                .takeUntilOther(Mono.delay(streamTotalTimeout)
                        .then(Mono.error(() -> new TimeoutException(
                                "AI 응답 생성이 전체 기한(" + streamTotalTimeout.toSeconds() + "초)을 넘겼습니다."))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
