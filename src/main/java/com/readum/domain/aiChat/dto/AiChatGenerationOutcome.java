package com.readum.domain.aiChat.dto;

/**
 * 스트리밍 생성 한 턴의 최종 판정 결과. {@link com.readum.domain.aiChat.service.AiChatGenerationAccumulator}
 * 가 청크를 순서대로 받아 누적한 뒤 만들어 낸다.
 *
 * <p>성공이면 저장·정산에 필요한 값(본문·실측 사용량)이 모두 채워져 있다. 실패면 {@link #status} 가 어떤 조건에서
 * 어긋났는지 알려주고, 본문은 <b>받은 데까지</b>의 조각이므로 저장에 쓰지 않는다(설계 정본 §6.3: 실패 시 본문 미저장).
 */
public record AiChatGenerationOutcome(
        Status status,
        String content,
        String finishReason,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {

    /**
     * 판정 결과. 성공은 하나뿐이고 나머지는 모두 실패다.
     *
     * <p>실패 사유가 동시에 여럿일 수 있으므로(예: 종료 사유도 없고 사용량도 없음) 여기 담기는 값은
     * <b>가장 먼저 걸린 한 가지</b>다 — 사람이 원인을 좁히기 위한 표시이지 실패 조건의 전부가 아니다.
     */
    public enum Status {
        /** 오류·기한 초과 없이 스트림이 끝났고, 종료 사유가 STOP 이며, 유효한 최종 사용량과 쓸 수 있는 본문이 있다. */
        SUCCESS,
        /** 스트림이 오류로 끝났다(onError). 그전에 STOP·사용량을 이미 받았더라도 성공으로 바꾸지 않는다. */
        STREAM_ERROR,
        /** 생성 기한(전체 또는 무응답)에 걸려 끊었다. */
        TIMED_OUT,
        /** 스트림은 끝났는데 공급자가 준 종료 사유가 한 번도 없었다 — 메타데이터 누락. */
        NO_FINISH_REASON,
        /** 종료 사유가 STOP 이 아니다(예: 답변이 잘린 LENGTH). 별도 저장·청구 예외를 두지 않고 실패로 보낸다. */
        ABNORMAL_FINISH_REASON,
        /** 정산에 쓸 수 있는 최종 사용량을 끝내 받지 못했다. 추정치로 대신하지 않는다. */
        NO_USAGE,
        /** 종료 사유·사용량은 왔지만 본문이 비어 사용자에게 보여줄 답변이 없다. */
        EMPTY_CONTENT
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
