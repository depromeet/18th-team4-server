package com.readum.domain.aiChat.dto;

import com.readum.domain.exception.RateLimitInfo;

import java.time.LocalDateTime;

public sealed interface MessageStreamEvent
        permits MessageStreamEvent.Token, MessageStreamEvent.Done, MessageStreamEvent.Replace,
                MessageStreamEvent.Error {

    record Token(String delta) implements MessageStreamEvent {}

    /**
     * 델타를 끝까지 보낸 연결의 정상 종료. 클라이언트가 이어붙여 온 부분 답변이 곧 최종 답변이므로 본문을 싣지 않는다.
     * messageId 는 클라이언트가 사용하지 않아 payload 에서 제외한다(필요해지면 이력 조회로 받는다).
     */
    record Done(
            TokenCount tokenCount,
            LocalDateTime createdAt
    ) implements MessageStreamEvent {
    }

    /**
     * 전달 큐가 포화돼 델타 전송을 포기한 연결의 정상 종료. 클라이언트가 지금까지 표시한 부분 답변을
     * <b>이어붙이지 않고 content 로 통째로 교체</b>한다. 델타를 중간에 버렸으므로 부분 답변은 최종 답변과 다르다.
     *
     * <p>보내는 시점은 <b>답변 저장·정산이 커밋된 뒤</b>로 한정한다 — 커밋되지 않은 본문을 최종 답변으로 보여 주면
     * 조회했을 때의 결과와 어긋난다. 그래서 실패한 턴에는 이 이벤트가 없고, 대신 error 로 끝난다.
     */
    record Replace(
            String content,
            TokenCount tokenCount,
            LocalDateTime createdAt
    ) implements MessageStreamEvent {
    }

    // SSE 응답이 이미 commit 된 상태(text/event-stream) 에서 발생한 에러는 HTTP 헤더로 메타정보를
    // 전달할 수 없으므로, 429 의 X-RateLimit-* 정보는 이 payload 의 rateLimitInfo 로 운반한다.
    // 429 가 아닌 일반 에러에서는 null.
    record Error(String code, String message, RateLimitInfo rateLimitInfo) implements MessageStreamEvent {

        public static Error of(String code, String message) {
            return new Error(code, message, null);
        }
    }

    record TokenCount(Integer input, Integer output, Integer total) {
    }
}
