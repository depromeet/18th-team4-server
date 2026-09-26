package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;

/**
 * 복구 확인 호출의 공통 앞단 — 자기 기능을 들고 있고, 호출의 예외를 결과 값으로 바꾼다.
 *
 * <p>기능을 인자로 받지 않고 객체가 들고 있는 이유: 확인 하나는 한 프로젝트의 키·모델·엔드포인트로만 나가는데,
 * 호출 지점이 매번 기능을 넘기면 한 곳만 틀려도 호출은 A 프로젝트로 나가고 상태는 B 기능에 쌓인다.
 *
 * <p><b>확인은 미리 걸러지지 않는다.</b> 예전에는 확인도 우리가 추정으로 돌리던 전역 게이트에서 자리를 얻어야
 * 나갈 수 있었고, 자리가 없으면 그 회차를 미뤘다. 그 게이트를 걷어낸 지금 확인을 막는 것은 임차뿐이다 —
 * 한 기능의 확인은 어느 순간에도 전체에서 하나이고({@code probeAdmit} 의 임차), 확인 한 건의 크기는
 * 출력 상한 몇 토큰으로 묶여 있다. 그 둘이면 확인이 정상 트래픽의 몫을 잠식하지 않는다.
 */
abstract class CapabilityRecoveryProbe implements AiProviderRecoveryProbe {

    private final AiAvailability.Capability capability;

    CapabilityRecoveryProbe(AiAvailability.Capability capability) {
        this.capability = capability;
    }

    @Override
    public final AiAvailability.Capability capability() {
        return capability;
    }

    @Override
    public final ProbeOutcome probe() {
        try {
            callProvider();
            return new ProbeOutcome.Verified();
        } catch (RuntimeException probeFailure) {
            return new ProbeOutcome.Failed(probeFailure);
        }
    }

    /**
     * 그 기능이 평소 쓰는 경로로 가장 작은 요청을 한 번 보내고, <b>유효한 판정까지</b> 확인한다.
     * 실패는 그대로 던진다 — 분류는 부르는 쪽이 한다.
     */
    protected abstract void callProvider();
}
