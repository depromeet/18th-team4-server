package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * OpenAI 호출 전에 전역 게이트 통과를 확보한다 — 막히면 {@link TooManyRequestsException} 으로 번역해 던진다.
 * 게이트({@link OpenAiRequestGate})는 Permitted/Rejected 판정만 하고, 그 거절을 도메인 공용 예외로
 * 번역하는 정책은 이 한 곳이 소유한다(예전엔 각 OpenAI 클라이언트가 같은 블록을 복제했다).
 * 거절 이후 무엇을 할지(429 응답·재큐·무벌점 반납·스킵)는 이 예외를 받는 각 호출자가 정한다.
 */
@Component
@RequiredArgsConstructor
public class OpenAiRateLimitGuard {

    private final OpenAiRequestGate gate;

    /**
     * 분당 예산에서 "요청 1 + 추정 토큰"을 확보한다. 통과하면 계상 내역을 반환하고, 막히면 던진다.
     * 계상 내역은 생성 실패 시 {@link OpenAiRequestGate#compensate} 로 되돌리기 위한 것이고,
     * fail-open 통과는 계상이 없었으므로 빈 Optional 이다. 보상하지 않는 호출 경로
     * (제목·감상문·요약)는 반환값을 무시하면 기존과 동일하게 동작한다.
     *
     * @throws TooManyRequestsException 분당 예산 포화(AI_RATE_LIMIT_BURST)
     *                                  또는 계정 quota 쿨다운(AI_QUOTA_EXHAUSTED)
     */
    public Optional<OpenAiRequestGate.GateReservation> acquireOrThrow(String model, int estimatedTokens) {
        return switch (gate.tryAcquire(model, estimatedTokens)) {
            case OpenAiRequestGate.Decision.Permitted(OpenAiRequestGate.GateReservation reservation) ->
                    Optional.of(reservation);
            case OpenAiRequestGate.Decision.PermittedUncounted() -> Optional.empty();
            case OpenAiRequestGate.Decision.Rejected rejected -> throw toTooManyRequests(rejected);
        };
    }

    /**
     * 확보했던 계상을 되돌린다 — {@link OpenAiRequestGate#compensate} 로의 단순 위임이다.
     * 확보와 보상이 같은 협력자를 거치게 해서, 게이트 접근을 이 한 곳이 소유한다는 취지를 유지한다
     * (호출자가 확보는 이 가드로, 보상은 게이트로 나눠 들지 않게 한다).
     * 보상 여부의 판단(생성이 실제로 토큰을 소모했는지)은 호출자의 몫이다.
     */
    public void compensate(OpenAiRequestGate.GateReservation reservation) {
        gate.compensate(reservation);
    }

    private TooManyRequestsException toTooManyRequests(OpenAiRequestGate.Decision.Rejected rejected) {
        AiChatErrorCode code = rejected.reason() == OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN
                ? AiChatErrorCode.AI_QUOTA_EXHAUSTED
                : AiChatErrorCode.AI_RATE_LIMIT_BURST;
        return new TooManyRequestsException(code, RateLimitInfo.retryAfterOnly(rejected.retryAfter()));
    }
}
