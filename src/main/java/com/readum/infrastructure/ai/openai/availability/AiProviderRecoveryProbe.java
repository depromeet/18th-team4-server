package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;

/**
 * 한 기능의 복구를 실제 호출로 확인한다.
 *
 * <p><b>무엇이 확인인가.</b> 그 기능이 평소 쓰는 <b>프로젝트 키 · 모델 · 엔드포인트 · 전송 방식</b> 으로
 * 가장 작은 요청을 한 번 보내고, 그 경로가 끝까지 성립하는지 본다. 모델 목록 조회나 공급자의 전체 상태 페이지로는
 * 확인하지 않는다 — 그런 것이 200 을 주는 동안에도 우리 프로젝트의 결제나 키가 막혀 있을 수 있고,
 * 반대로 다른 프로젝트의 장애가 우리를 막아 둔 것처럼 보이게 한다.
 *
 * <p>확인은 차단된 기능에만, 기한이 됐을 때만 일어난다. 정상인 기능은 확인하지 않는다.
 */
public interface AiProviderRecoveryProbe {

    /** 이 확인이 담당하는 기능. */
    AiAvailability.Capability capability();

    /**
     * 확인 호출 한 번. 던지지 않고 결과를 값으로 돌려준다 — 부르는 쪽(스케줄러)이 결과마다 다르게
     * 기록해야 하는데, 예외로 섞어 보내면 그 구분이 호출자의 catch 순서에 달리게 된다.
     */
    ProbeOutcome probe();

    /** 확인의 결과 두 가지. */
    sealed interface ProbeOutcome {

        /** 유효한 판정까지 받았다 — 이 기능은 다시 쓸 수 있다. */
        record Verified() implements ProbeOutcome {
        }

        /** 확인이 실패했다 — 원인은 분류기가 판정한다(공급자 실패인지, 우리 쪽 오류인지). */
        record Failed(RuntimeException cause) implements ProbeOutcome {
        }
    }
}
