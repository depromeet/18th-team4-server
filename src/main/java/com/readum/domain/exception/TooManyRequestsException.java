package com.readum.domain.exception;

public class TooManyRequestsException extends BusinessException {

    private final Long retryAfterSeconds;

    public TooManyRequestsException(ErrorCode errorCode) {
        super(errorCode);
        this.retryAfterSeconds = null;
    }

    /**
     * @param retryAfterSeconds 클라이언트가 재시도 전 대기해야 할 초 단위 시간.
     *                          null 이면 Retry-After 헤더를 내보내지 않는다.
     *                          서비스마다 retry 간격이 다를 수 있으므로 호출자가 직접 결정한다.
     */
    public TooManyRequestsException(ErrorCode errorCode, Long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
