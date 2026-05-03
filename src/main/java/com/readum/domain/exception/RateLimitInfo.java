package com.readum.domain.exception;

import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

// 외부 시스템(OpenAI 등) 의 rate limit 응답 헤더에서 추출한 메타정보.
// TooManyRequestsException 에 동봉되어 GlobalExceptionHandler / SSE error payload 로 운반된다.
//
// 모든 필드는 nullable. 외부 시스템마다 제공하는 정보가 다르므로(요청 한도만, 토큰 한도만, 등)
// 채워진 필드만 응답에 반영하고 누락 필드는 헤더/payload 에 포함시키지 않는다.
//
// retryAfter: 표준 HTTP "Retry-After" 헤더에 매핑. 클라이언트가 자동 재시도 간격으로 사용.
// reset*: 다음 한도 리셋까지 남은 시간. retryAfter 가 없을 때 폴백 정보.
public record RateLimitInfo(
        Duration retryAfter,
        Long limitRequests,
        Long limitTokens,
        Long remainingRequests,
        Long remainingTokens,
        Duration resetRequests,
        Duration resetTokens
) {

    public static RateLimitInfo empty() {
        return new RateLimitInfo(null, null, null, null, null, null, null);
    }

    public boolean hasAny() {
        return retryAfter != null
                || limitRequests != null
                || limitTokens != null
                || remainingRequests != null
                || remainingTokens != null
                || resetRequests != null
                || resetTokens != null;
    }

    // 캐논화된 한 곳에서만 필드↔이름 매핑을 정의한다.
    // 두 변환(toHttpHeaders / toPayloadMap) 이 같은 필드 집합을 다루므로 BiConsumer 로 일원화.
    private void emitFields(BiConsumer<Field, Long> emitter) {
        emit(emitter, Field.RETRY_AFTER, secondsOrNull(retryAfter));
        emit(emitter, Field.LIMIT_REQUESTS, limitRequests);
        emit(emitter, Field.LIMIT_TOKENS, limitTokens);
        emit(emitter, Field.REMAINING_REQUESTS, remainingRequests);
        emit(emitter, Field.REMAINING_TOKENS, remainingTokens);
        emit(emitter, Field.RESET_REQUESTS, secondsOrNull(resetRequests));
        emit(emitter, Field.RESET_TOKENS, secondsOrNull(resetTokens));
    }

    private static void emit(BiConsumer<Field, Long> emitter, Field field, Long value) {
        if (value != null) {
            emitter.accept(field, value);
        }
    }

    private static Long secondsOrNull(Duration duration) {
        return duration == null ? null : duration.getSeconds();
    }

    // HTTP 응답 헤더로 노출할 형태. 외부 REST 경로에서 사용.
    public Map<String, String> toHttpHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        emitFields((field, value) -> headers.put(field.headerName, value.toString()));
        return headers;
    }

    // SSE error event 등 JSON 페이로드로 노출할 형태. 헤더와 키 컨벤션이 다르다 (camelCase).
    public Map<String, Object> toPayloadMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        emitFields((field, value) -> payload.put(field.payloadKey, value));
        return payload;
    }

    private enum Field {
        RETRY_AFTER(HttpHeaders.RETRY_AFTER, "retryAfterSeconds"),
        LIMIT_REQUESTS("X-RateLimit-Limit-Requests", "limitRequests"),
        LIMIT_TOKENS("X-RateLimit-Limit-Tokens", "limitTokens"),
        REMAINING_REQUESTS("X-RateLimit-Remaining-Requests", "remainingRequests"),
        REMAINING_TOKENS("X-RateLimit-Remaining-Tokens", "remainingTokens"),
        RESET_REQUESTS("X-RateLimit-Reset-Requests", "resetRequestsSeconds"),
        RESET_TOKENS("X-RateLimit-Reset-Tokens", "resetTokensSeconds");

        private final String headerName;
        private final String payloadKey;

        Field(String headerName, String payloadKey) {
            this.headerName = headerName;
            this.payloadKey = payloadKey;
        }
    }
}
