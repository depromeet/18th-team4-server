package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;

public interface AiChatClient {

    /**
     * 전역 게이트 확보 결과. 도메인은 내용을 해석하지 않고, 보상이 필요한 경로에서
     * {@link #releaseRateLimitPermit(RateLimitPermit)} 에 그대로 돌려준다.
     */
    sealed interface RateLimitPermit {
        /** 분당 예산에 계상된 확보 — 생성이 실패해 토큰 소모가 없으면 release 로 보상 차감한다. */
        record Counted(String model, long epochMinute, int estimatedTokens) implements RateLimitPermit {
        }

        /** 계상 없는 통과(게이트 fail-open) — release 는 아무것도 하지 않는다. */
        record Uncounted() implements RateLimitPermit {
        }
    }

    /**
     * OpenAI 전역 게이트 통과를 확보한다. 반드시 generate() 전, SSE 시작 전(사전 단계)에 호출해
     * 거절이 HTTP 429 JSON 으로 나가게 한다. 거절 시 TooManyRequestsException 을 던진다.
     * 통과 시 반환하는 permit 은 생성 실패 시 보상 차감(releaseRateLimitPermit)에 쓰인다.
     */
    RateLimitPermit acquireRateLimitPermit(AiChatStreamCommand command);

    /**
     * 확보했던 게이트 계상을 보상 차감한다 — 생성이 실패했거나 시작되지 않아
     * OpenAI 가 실제로 토큰을 소모하지 않은 경우에만 호출한다.
     * 계상 없는 permit(fail-open 통과)이면 아무것도 하지 않는다.
     */
    void releaseRateLimitPermit(RateLimitPermit permit);

    /** 비스트리밍 동기 호출. 완성 응답을 반환하고, 실패는 예외로 던진다. 호출 전 acquireRateLimitPermit() 이 선행되어야 한다. */
    AiChatCompletion generate(AiChatStreamCommand command);
}
