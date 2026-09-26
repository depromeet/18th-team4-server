package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import reactor.core.publisher.Flux;

public interface AiChatClient {

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

    /**
     * 스트리밍 호출. 본문 조각을 청크로 흘려보내고, 종료 사유와 실측 사용량을 마지막 청크들에 싣는다.
     * 실패는 스트림의 error 신호로 전달한다.
     *
     * <p>청크는 전송 계층(reactor-netty) 스레드에서 방출된다. 구독자는 청크 경로에서 블로킹 작업을 하지 않고,
     * SSE 쓰기·저장·정산은 별도 실행 주체로 넘긴다.
     *
     * <p>이 스트림이 끝났다는 것(onComplete)은 수신이 끝났다는 뜻이지 생성이 성공했다는 뜻이 아니다.
     * 정상 완료 판정은 {@link com.readum.domain.aiChat.service.AiChatGenerationAccumulator} 가 맡는다.
     */
    Flux<AiChatStreamChunk> generateStream(AiChatStreamCommand command);
}
