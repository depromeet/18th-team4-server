package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 모델 호출 하나를 감싸는 보호의 계약. 핵심은 <b>무엇을 성공으로 볼 것인가</b> 다 —
 * 스트림이 만들어진 것도, 첫 응답이 온 것도 성공이 아니다.
 */
@ExtendWith(MockitoExtension.class)
class AiProviderCallGuardTest {

    private static final AiAvailability.Capability CAPABILITY = AiAvailability.Capability.CHAT;

    @Mock
    private AiProviderCircuitBreaker circuitBreaker;

    private final AiProviderFailureClassifier failureClassifier = new AiProviderFailureClassifier();

    private AiProviderCallGuard guard() {
        return new AiProviderCallGuard(circuitBreaker, failureClassifier);
    }

    private AiProviderCircuitBreaker.CallPermit permit() {
        return new AiProviderCircuitBreaker.CallPermit(CAPABILITY, 3L, System.nanoTime());
    }

    @Test
    void 스트림을_만들기만_하고_구독하지_않으면_허가를_잡지_않는다() {
        // 스트림 객체가 생겼다는 것은 호출이 나갔다는 뜻이 아니다. 구독 시점에 허가를 받아야
        // "만들었으나 보내지 않은" 호출이 상태를 흔들지 않는다.
        guard().streamProtected(CAPABILITY, () -> Flux.just("조각"));

        verify(circuitBreaker, never()).admit(any());
    }

    @Test
    void 구독해서_끝까지_흐르면_성공으로_남긴다() {
        AiProviderCircuitBreaker.CallPermit permit = permit();
        given(circuitBreaker.admit(CAPABILITY)).willReturn(permit);

        StepVerifier.create(guard().streamProtected(CAPABILITY, () -> Flux.just("가", "나")))
                .expectNext("가", "나")
                .verifyComplete();

        verify(circuitBreaker).recordSuccess(permit);
    }

    @Test
    void 응답이_시작된_뒤_끊기면_실패로_남긴다() {
        // HTTP 200 과 첫 조각까지 받았어도 끝을 못 맺었으면 그 호출은 실패다.
        // 스트림 생성 시점만 보는 보호는 이 경우를 통째로 놓친다.
        AiProviderCircuitBreaker.CallPermit permit = permit();
        given(circuitBreaker.admit(CAPABILITY)).willReturn(permit);

        StepVerifier.create(guard().streamProtected(
                        CAPABILITY, () -> Flux.just("가").concatWith(Flux.error(new IOException("연결 끊김")))))
                .expectNext("가")
                .verifyError(IOException.class);

        ArgumentCaptor<AiProviderFailureClassifier.Classification> recorded =
                ArgumentCaptor.forClass(AiProviderFailureClassifier.Classification.class);
        verify(circuitBreaker).recordFailure(org.mockito.ArgumentMatchers.eq(permit), recorded.capture());
        assertThat(recorded.getValue().kind()).isEqualTo(AiProviderFailureKind.TRANSIENT);
    }

    @Test
    void 맨_취소는_상태에_아무것도_남기지_않는다() {
        // 취소는 공급자 사정이 아니라 우리 쪽 사정(구독 해제·종료 절차)일 수 있다. 그것을 실패로 세면
        // 동시에 여러 건이 취소될 때 멀쩡한 공급자의 공유 차단이 열린다. 성공으로 세도 안 된다 —
        // 공급자의 응답을 끝까지 받아 본 것이 아니라 관찰 창의 표본이 될 수 없다.
        AiProviderCircuitBreaker.CallPermit permit = permit();
        given(circuitBreaker.admit(CAPABILITY)).willReturn(permit);

        Flux<String> protectedStream = guard()
                .streamProtected(CAPABILITY, () -> Flux.<String>never().startWith("가"))
                .timeout(Duration.ofMillis(100));

        StepVerifier.create(protectedStream)
                .expectNext("가")
                .verifyError(TimeoutException.class);

        verify(circuitBreaker, never()).recordFailure(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(circuitBreaker, never()).recordSuccess(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 보호_구간_안에서_난_기한_초과는_오류로_도착해_일시_실패로_센다() {
        // 기한을 이 안쪽에 걸기 때문에(ProtectedChatModel) 기한 초과가 취소가 아니라 오류 신호로 온다 —
        // 그래야 "응답이 끊긴 공급자" 를 감지할 수 있다. 위의 맨 취소와 구분되는 지점이다.
        AiProviderCircuitBreaker.CallPermit permit = permit();
        given(circuitBreaker.admit(CAPABILITY)).willReturn(permit);

        StepVerifier.create(guard().streamProtected(
                        CAPABILITY,
                        () -> Flux.<String>never().startWith("가").timeout(Duration.ofMillis(100))))
                .expectNext("가")
                .verifyError(TimeoutException.class);

        ArgumentCaptor<AiProviderFailureClassifier.Classification> recorded =
                ArgumentCaptor.forClass(AiProviderFailureClassifier.Classification.class);
        verify(circuitBreaker).recordFailure(org.mockito.ArgumentMatchers.eq(permit), recorded.capture());
        assertThat(recorded.getValue().kind()).isEqualTo(AiProviderFailureKind.TRANSIENT);
    }

    @Test
    void 스트림을_만들다_그_자리에서_터져도_결과를_남긴다() {
        // 종료 훅이 달리기 전이라 아무도 남겨 주지 않는다 — 남기지 않으면 스트림을 못 여는 공급자의
        // 실패가 상태에 전혀 쌓이지 않는다.
        AiProviderCircuitBreaker.CallPermit permit = permit();
        given(circuitBreaker.admit(CAPABILITY)).willReturn(permit);

        StepVerifier.create(guard().streamProtected(CAPABILITY, () -> {
                    throw new IllegalStateException("스트림 조립 실패");
                }))
                .verifyError(IllegalStateException.class);

        verify(circuitBreaker).recordFailure(org.mockito.ArgumentMatchers.eq(permit), any());
    }

    @Test
    void 차단_중이면_구독_시점에_거절되고_모델을_부르지_않는다() {
        given(circuitBreaker.admit(CAPABILITY))
                .willThrow(new AiDependencyUnavailableException(AiChatErrorCode.AI_PROVIDER_UNAVAILABLE));
        boolean[] modelCalled = { false };

        StepVerifier.create(guard().streamProtected(CAPABILITY, () -> {
                    modelCalled[0] = true;
                    return Flux.just("조각");
                }))
                .verifyError(AiDependencyUnavailableException.class);

        assertThat(modelCalled[0]).isFalse();
    }

    @Test
    void 한_번에_끝나는_호출도_성공과_실패를_남긴다() {
        AiProviderCircuitBreaker.CallPermit permit = permit();
        given(circuitBreaker.admit(CAPABILITY)).willReturn(permit);

        assertThat(guard().callProtected(CAPABILITY, () -> "결과")).isEqualTo("결과");
        verify(circuitBreaker).recordSuccess(permit);

        // 공급자 사정으로 판정된 실패는 도메인이 한눈에 알아보는 형태로 바뀌어 올라온다 —
        // 작업 큐가 "이 작업이 잘못됐다" 와 "지금 공급자를 못 쓴다" 를 다르게 처리해야 하기 때문이다.
        // 원인은 매달려 있어 운영에서 무엇 때문이었는지를 잃지 않는다.
        assertThatThrownBy(() -> guard().callProtected(CAPABILITY, () -> {
            throw new org.springframework.ai.retry.TransientAiException("503");
        })).isInstanceOf(AiDependencyUnavailableException.class)
                .hasCauseInstanceOf(org.springframework.ai.retry.TransientAiException.class);

        ArgumentCaptor<AiProviderFailureClassifier.Classification> recorded =
                ArgumentCaptor.forClass(AiProviderFailureClassifier.Classification.class);
        verify(circuitBreaker).recordFailure(org.mockito.ArgumentMatchers.eq(permit), recorded.capture());
        assertThat(recorded.getValue().kind()).isEqualTo(AiProviderFailureKind.TRANSIENT);
    }

    @Test
    void 세지_않는_실패는_감싸지_않고_그대로_올려보낸다() {
        // 우리 쪽 DB·변환 오류까지 "공급자를 못 쓴다" 로 바꾸면 큐가 시도 횟수를 쓰지 않고 영원히 되돌린다.
        AiProviderCircuitBreaker.CallPermit permit = permit();
        given(circuitBreaker.admit(CAPABILITY)).willReturn(permit);

        assertThatThrownBy(() -> guard().callProtected(CAPABILITY, () -> {
            throw new IllegalStateException("DB 매핑 오류");
        })).isInstanceOf(IllegalStateException.class);
    }
}
