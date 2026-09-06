package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import reactor.core.publisher.Flux;

public interface AiChatClient {

    /**
     * 전역 게이트 확보 결과. 도메인은 내용을 해석하지 않고, 보상이 필요한 경로에서
     * {@link #releaseRateLimitPermit(RateLimitPermit)} 에 그대로 돌려준다.
     */
    sealed interface RateLimitPermit {
        /** 분당 예산에 계상된 확보 — 생성이 실패해 토큰 소모가 없으면 release 로 보상 차감한다. */
        record Counted(String model, long epochMinute, int estimatedTokens) implements RateLimitPermit {
        }

        /** 게이트가 검사 없이 통과시킨 경우 — 계상된 것이 없으므로 release 는 아무것도 하지 않는다. */
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
     * 게이트가 검사 없이 통과시켜 계상이 없는 permit 이면 아무것도 하지 않는다.
     */
    void releaseRateLimitPermit(RateLimitPermit permit);

    /**
     * 로컬 입력 검사 — 이 요청을 모델에 보내지 않고 거절해야 하는지 판정한다.
     * 정규식 패턴과 금칙어를 로컬에서 대조할 뿐이라 외부 호출도 과금도 없다.
     *
     * <p>검사 대상은 <b>조립된 프롬프트 전체</b>(시스템 메시지 + 이력 + 이번 입력)다. 프롬프트 조립은
     * 구현체가 하므로 판정도 이 port 에 둔다 — 도메인이 조립을 다시 흉내 내면 무엇을 막는지가 갈린다.
     *
     * <p>차단 판정은 선행 단계에서 입력 moderation 차단과 같은 모양으로 거절한다
     * (REJECTED 기록 + 400). 거부 문구를 답변처럼 흘려보내지 않는다.
     */
    boolean isBlockedByLocalInputCheck(AiChatStreamCommand command);

    /** 비스트리밍 동기 호출. 완성 응답을 반환하고, 실패는 예외로 던진다. 호출 전 acquireRateLimitPermit() 이 선행되어야 한다. */
    AiChatCompletion generate(AiChatStreamCommand command);

    /**
     * 스트리밍 호출. 본문 조각을 청크로 흘려보내고, 종료 사유와 실측 사용량을 마지막 청크들에 싣는다.
     * 실패는 스트림의 error 신호로 전달한다. 호출 전 acquireRateLimitPermit() 이 선행되어야 한다.
     *
     * <p>청크는 전송 계층(reactor-netty) 스레드에서 방출된다. 구독자는 청크 경로에서 블로킹 작업을 하지 않고,
     * SSE 쓰기·저장·정산은 별도 실행 주체로 넘긴다.
     *
     * <p>이 스트림이 끝났다는 것(onComplete)은 수신이 끝났다는 뜻이지 생성이 성공했다는 뜻이 아니다.
     * 정상 완료 판정은 {@link com.readum.domain.aiChat.service.AiChatGenerationAccumulator} 가 맡는다.
     */
    Flux<AiChatStreamChunk> generateStream(AiChatStreamCommand command);
}
