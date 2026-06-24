package com.readum.domain.exception;

public class TooManyRequestsException extends BusinessException {

    private final RateLimitInfo rateLimitInfo;

    public TooManyRequestsException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    // rate limit 메타(헤더 정보) 를 함께 운반하는 형태.
    // 외부 시스템 어댑터(예: OpenAiResponseErrorHandler) 가 응답 헤더를 RateLimitInfo 로 추출해
    // 여기에 실어 보내면, GlobalExceptionHandler 가 X-RateLimit-* / Retry-After 헤더로,
    // SSE error event 가 payload 로 그대로 노출한다.
    public TooManyRequestsException(ErrorCode errorCode, RateLimitInfo rateLimitInfo) {
        super(errorCode);
        this.rateLimitInfo = rateLimitInfo;
    }

    public RateLimitInfo getRateLimitInfo() {
        return rateLimitInfo;
    }
}
