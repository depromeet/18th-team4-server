package com.readum.domain.exception;

/**
 * 일시적으로 요청을 처리할 수 없는 상태(HTTP 503).
 * 외부 의존(예: Moderation API) 장애로 fail-closed 처리할 때 사용한다.
 * 클라이언트 재시도 간격 안내를 위해 Retry-After 초를 동봉한다.
 */
public class ServiceUnavailableException extends BusinessException {

    private static final long DEFAULT_RETRY_AFTER_SECONDS = 30L;

    private final long retryAfterSeconds;

    public ServiceUnavailableException(ErrorCode errorCode) {
        this(errorCode, DEFAULT_RETRY_AFTER_SECONDS);
    }

    public ServiceUnavailableException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
