package com.readum.infrastructure.ai.openai.ratelimit;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
     * 분당 예산에서 "요청 1 + 추정 토큰"을 확보한다. 통과하면 조용히 반환, 막히면 던진다.
     *
     * @throws TooManyRequestsException 분당 예산 포화(AI_RATE_LIMIT_BURST)
     *                                  또는 계정 quota 쿨다운(AI_QUOTA_EXHAUSTED)
     */
    public void acquireOrThrow(String model, int estimatedTokens) {
        OpenAiRequestGate.Decision decision = gate.tryAcquire(model, estimatedTokens);
        if (decision instanceof OpenAiRequestGate.Decision.Rejected rejected) {
            AiChatErrorCode code = rejected.reason() == OpenAiRequestGate.RejectReason.QUOTA_COOLDOWN
                    ? AiChatErrorCode.AI_QUOTA_EXHAUSTED
                    : AiChatErrorCode.AI_RATE_LIMIT_BURST;
            throw new TooManyRequestsException(code, RateLimitInfo.retryAfterOnly(rejected.retryAfter()));
        }
    }
}
