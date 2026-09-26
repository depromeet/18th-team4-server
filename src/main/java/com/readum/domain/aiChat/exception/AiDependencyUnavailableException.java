package com.readum.domain.aiChat.exception;

import com.readum.domain.exception.ErrorCode;
import com.readum.domain.exception.ServiceUnavailableException;

/**
 * AI 공급자가 장애로 판정돼 차단 중이거나, 차단 여부를 확인할 수 없어 새 호출을 시작하지 않는 상태(HTTP 503).
 *
 * <p>작업 큐는 이 예외를 <b>작업의 실패가 아니라 지금 보낼 수 없음</b> 으로 읽어, 시도 횟수를 올리지 않고
 * 작업을 대기열에 되돌린다. 그래서 공급자가 오래 막혀 있어도 작업이 재시도 상한을 소진해 버리지 않는다.
 */
public class AiDependencyUnavailableException extends ServiceUnavailableException {

    public AiDependencyUnavailableException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AiDependencyUnavailableException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode, retryAfterSeconds);
    }
}
