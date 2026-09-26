package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.out.AiAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 도메인이 보는 가용 상태를 장애 차단기({@link AiProviderCircuitBreaker}) 하나로 답한다.
 *
 * <p><b>예전에는 상태가 둘이었다.</b> 실패를 세어 여는 차단 말고도, 결제·지출 한도 오류를 본 지점이 따로
 * 적어 두는 시간제 쿨다운이 있었다. 둘은 같은 계기로 열리고 같은 대상을 막는데 해제 조건만 달라
 * (한쪽은 시간이 지나면, 한쪽은 실제 호출이 성공해야) 상태가 갈렸고, 도메인은 어느 쪽이 막았는지 알 수 없는
 * 채로 둘을 모두 물어야 했다. 지금은 결제 오류도 차단기의 차단 한 가지로 모이므로 물을 곳이 하나다.
 *
 * <p>이름에서 저장소를 뺀 이유: 차단 상태는 더 이상 Redis 에 있지 않고 이 서버의 메모리에만 있다.
 * 그 한계(서버마다 따로 막힌다·재시작하면 정상에서 시작한다)는 {@link AiProviderCircuitBreaker} 에 적었다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAvailabilityAdapter implements AiAvailability {

    private final AiProviderCircuitBreaker circuitBreaker;

    @Override
    public void requireAvailable(Capability capability) {
        circuitBreaker.requireNewWorkAllowed(capability);
    }

    @Override
    public boolean canProcess(Capability capability) {
        return circuitBreaker.isProcessingAllowed(capability);
    }

    @Override
    public void onQueueDrained(Capability capability) {
        circuitBreaker.markQueueDrained(capability);
    }
}
