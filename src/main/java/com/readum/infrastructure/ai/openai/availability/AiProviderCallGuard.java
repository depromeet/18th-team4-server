package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 모델 호출 하나를 공급자 상태 보호로 감싼다 — 나가기 직전에 허가를 받고, 끝나는 모양대로 결과를 남긴다.
 * 다섯 기능이 같은 규칙을 쓰도록 이 한 곳에만 둔다.
 *
 * <p><b>스트리밍에서 무엇을 관측하는가.</b> 스트림은 만들어졌다는 것도, HTTP 200 을 받았다는 것도 성공이 아니다.
 * 실제로 구독되어(= 호출이 나가서) 끝까지 흘렀을 때만 성공이다. 그래서 허가는 구독 시점에 받고,
 * 판정은 종료 신호(정상 종료 · 오류 · 취소)에서 한다.
 *
 * <p><b>기한은 이 안에서 건다.</b> 응답 기한을 바깥에 걸면 기한 초과가 이 구독에는 <b>취소</b> 로만 보인다 —
 * 취소는 종료 절차·구독 해제 등 다른 이유로도 오므로, 그것을 실패로 세면 공급자와 무관한 일로 공유 차단이 열린다.
 * 반대로 세지 않으면 응답이 끊긴 공급자를 영영 감지하지 못한다. 그래서 기한을 이 보호 구간 <b>안쪽</b>에서 걸어
 * 기한 초과가 오류 신호로 도착하게 하고({@link java.util.concurrent.TimeoutException}), <b>맨 취소는 세지 않는다</b>.
 * 타이머는 여전히 하나뿐이다 — 바깥(도메인)에서 걸던 것을 이리로 옮겨 온 것이지 새로 더한 것이 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProviderCallGuard {

    private final AiProviderCircuitBreaker circuitBreaker;
    private final AiProviderFailureClassifier failureClassifier;

    /** 한 번에 끝나는 호출(감상문 · 컨텍스트 요약 · 제목 · 입력 검토)을 감싼다. */
    public <T> T callProtected(AiAvailability.Capability capability, Supplier<T> call) {
        AiProviderCircuitBreaker.CallPermit permit = circuitBreaker.admit(capability);
        AtomicBoolean settled = new AtomicBoolean(false);
        T result;
        try {
            result = call.get();
        } catch (RuntimeException callFailure) {
            AiProviderFailureClassifier.Classification classification = failureClassifier.classify(callFailure);
            settle(settled, () -> circuitBreaker.recordFailure(permit, classification));
            throw asProviderOutage(capability, classification, callFailure);
        }
        settle(settled, () -> circuitBreaker.recordSuccess(permit));
        return result;
    }

    /**
     * 공급자 사정으로 판정된 실패는 <b>도메인이 한눈에 알아보는 형태</b>로 바꿔 올려보낸다.
     *
     * <p>작업 큐는 "이 작업이 잘못됐다" 와 "지금 공급자를 못 쓴다" 를 다르게 처리해야 한다 — 앞은 시도 횟수를 쓰고,
     * 뒤는 시도 횟수를 쓰지 않고 되돌려야 공급자가 몇 시간 막혀도 대기 중인 작업이 재시도 상한을 소진하지 않는다.
     * 그런데 5xx 는 {@code TransientAiException}, 연결 실패는 {@code ResourceAccessException} … 처럼 형태가 제각각이라
     * 호출자가 매번 같은 분류를 되풀이해야 했다. 분류는 이미 여기서 한 번 했으므로, 그 결과를 타입으로 실어 보낸다.
     *
     * <p>원인은 그대로 매단다 — 운영에서 "무엇 때문에 막혔는지" 를 잃지 않기 위해서다.
     * 세지 않는 실패(그 요청의 입력 오류, 우리 쪽 DB·변환 오류)는 <b>손대지 않고</b> 그대로 통과시킨다.
     */
    private RuntimeException asProviderOutage(
            AiAvailability.Capability capability,
            AiProviderFailureClassifier.Classification classification,
            RuntimeException callFailure
    ) {
        if (!classification.kind().counted()) {
            return callFailure;
        }
        if (callFailure instanceof AiDependencyUnavailableException alreadyTyped) {
            return alreadyTyped;
        }
        AiDependencyUnavailableException outage =
                new AiDependencyUnavailableException(AiChatErrorCode.AI_PROVIDER_UNAVAILABLE);
        outage.initCause(callFailure);
        log.info("AI 공급자 실패를 사용 불가로 올려보낸다 capability={} 종류={} 원인={}",
                capability, classification.kind(), callFailure.toString());
        return outage;
    }

    /**
     * 스트리밍 호출(채팅)을 감싼다. 허가는 구독 시점에 받으므로, 스트림을 만들어 두고 구독하지 않으면
     * 허가도 받지 않는다 — "만들었으나 보내지 않은" 호출이 상태를 흔들지 않는다.
     */
    public <T> Flux<T> streamProtected(AiAvailability.Capability capability, Supplier<Flux<T>> stream) {
        return Flux.defer(() -> {
            AiProviderCircuitBreaker.CallPermit permit = circuitBreaker.admit(capability);
            AtomicBoolean settled = new AtomicBoolean(false);
            Flux<T> source;
            try {
                source = stream.get();
            } catch (RuntimeException notEvenStarted) {
                // 스트림을 만드는 도중 그 자리에서 터졌다 — 종료 훅이 달리기 전이라 아무도 이 결과를 남겨 주지 않는다.
                // 여기서 남기지 않으면 공급자가 스트림을 열지 못하는 상황이 상태에 전혀 쌓이지 않는다.
                settle(settled, () -> circuitBreaker.recordFailure(
                        permit, failureClassifier.classify(notEvenStarted)));
                throw notEvenStarted;
            }
            return source
                    .doOnComplete(() -> settle(settled, () -> circuitBreaker.recordSuccess(permit)))
                    .doOnError(streamError -> settle(settled,
                            () -> circuitBreaker.recordFailure(permit, failureClassifier.classify(streamError))))
                    // 맨 취소는 세지 않는다 — 공급자가 아니라 우리 쪽 사정(구독 해제·종료 절차)일 수 있다.
                    // 응답이 끊긴 공급자는 이 안쪽에 걸린 기한이 오류 신호로 바꿔 주므로 위 doOnError 가 잡는다.
                    // 그래서 취소에는 기록할 것이 없다 — 판정 표식만 닫아 뒤늦게 오는 종료 신호가 겹쳐 기록하지 않게 한다.
                    .doOnCancel(() -> settled.set(true));
        });
    }

    /**
     * 종료 신호는 서로 배타적이지 않다(취소와 종료가 겹칠 수 있다). 먼저 도착한 하나만 기록해
     * 호출 한 건이 상태를 두 번 흔들지 않게 한다.
     *
     * <p><b>그 자리에서 기록한다.</b> 예전에는 상태가 Redis 에 있어 이벤트 루프를 막지 않으려고 가상 스레드로
     * 넘겼는데, 지금은 짧은 잠금 하나와 메모리 갱신이라 넘길 이유가 없다. 넘기면 한 건으로 바로 막아야 하는
     * 429·결제 오류가 실제로 막히기까지 늦어지고, 기록 순서도 뒤바뀔 수 있다.
     */
    private void settle(AtomicBoolean settled, Runnable record) {
        if (settled.compareAndSet(false, true)) {
            record.run();
        }
    }
}
