package com.readum.presentation.controller.aiChat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.exception.RateLimitInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

// AI 채팅 SSE 응답의 와이어 포맷 전담.
// 이벤트 이름(token/done/replace/error) 과 JSON payload 키 컨벤션을 한 곳에 모아
// AiChatController 가 HTTP 라우팅에만 집중할 수 있게 한다.
//
// 도메인 무관한 횡단 인프라가 아니라(presentation/common 의 GlobalApiResponse 같은) AI 채팅
// MessageStreamEvent 의 variant 와 JSON 스키마를 직접 알고 있는 feature-local 직렬화기.
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageStreamSseSerializer {

    private final ObjectMapper objectMapper;

    public SseEmitter.SseEventBuilder toSseEvent(MessageStreamEvent event) {
        return switch (event) {
            case MessageStreamEvent.Token token -> sse("token", new TokenPayload(token.delta()));
            case MessageStreamEvent.Done done -> sse("done", toDonePayload(done));
            case MessageStreamEvent.Replace replace -> sse("replace", toReplacePayload(replace));
            case MessageStreamEvent.Error error -> sse("error", toErrorPayload(error));
        };
    }

    private SseEmitter.SseEventBuilder sse(String eventName, Object payload) {
        return SseEmitter.event().name(eventName).data(toJson(payload));
    }

    private DonePayload toDonePayload(MessageStreamEvent.Done done) {
        return new DonePayload(toTokenCountPayload(done.tokenCount()), done.createdAt());
    }

    private TokenCountPayload toTokenCountPayload(MessageStreamEvent.TokenCount tokenCount) {
        if (tokenCount == null) {
            return null;
        }
        return new TokenCountPayload(tokenCount.input(), tokenCount.output(), tokenCount.total());
    }

    // 큐 포화로 델타 전송을 포기한 연결의 종료 이벤트. done 과 달리 최종 본문(content) 을 통째로 싣는다 —
    // 클라이언트는 지금까지 표시한 부분 답변에 이어붙이지 않고 이 본문으로 교체한다.
    private ReplacePayload toReplacePayload(MessageStreamEvent.Replace replace) {
        return new ReplacePayload(
                replace.content(),
                toTokenCountPayload(replace.tokenCount()),
                replace.createdAt()
        );
    }

    // SSE 응답은 이미 commit (status 200) 되어 X-RateLimit-* 헤더를 추가할 수 없으므로,
    // 429 메타정보(retryAfter / remaining / reset 등)는 error payload 의 rateLimit 키로 운반한다.
    // rateLimit 은 외부 provider 마다 채워진 필드가 달라(변동 shape) Map 으로 운반하고,
    // 키 컨벤션은 RateLimitInfo.Field enum 이 일원 관리한다. 비어있으면 null → @JsonInclude 가 키 생략.
    private ErrorPayload toErrorPayload(MessageStreamEvent.Error error) {
        RateLimitInfo info = error.rateLimitInfo();
        Map<String, Object> rateLimit = (info != null && info.hasAny()) ? info.toPayloadMap() : null;
        return new ErrorPayload(error.code(), error.message(), rateLimit);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            log.error("SSE payload 직렬화 실패", ex);
            return "{}";
        }
    }

    // SSE wire-format 전용 nested record 들. 외부에 노출되지 않으므로 private.
    // 직접 조립하던 LinkedHashMap 을 record 로 대체해 키 오타 / 순서 누락 위험을 컴파일타임에 차단한다.

    private record TokenPayload(String delta) {}

    private record TokenCountPayload(Integer input, Integer output, Integer total) {}

    // createdAt: 직접 LocalDateTime 으로 둬서 Jackson(JavaTimeModule) 이 일관되게 직렬화하도록 한다.
    // toString() 으로 String 화하면 다른 Response DTO 들(MessageResponse 등) 의 createdAt 직렬화
    // 형식과 어긋나, 같은 의미의 필드가 엔드포인트마다 다른 모양이 될 수 있다.
    private record DonePayload(TokenCountPayload tokenCount, LocalDateTime createdAt) {}

    private record ReplacePayload(String content, TokenCountPayload tokenCount, LocalDateTime createdAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ErrorPayload(String code, String message, Map<String, Object> rateLimit) {}
}
